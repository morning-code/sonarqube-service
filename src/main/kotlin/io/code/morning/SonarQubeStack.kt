package io.code.morning

import software.amazon.awscdk.core.*
import software.amazon.awscdk.services.autoscaling.AutoScalingGroup
import software.amazon.awscdk.services.autoscaling.AutoScalingGroupProps
import software.amazon.awscdk.services.ec2.*
import software.amazon.awscdk.services.ecs.*
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedEc2Service
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedEc2ServiceProps
import software.amazon.awscdk.services.logs.LogGroup
import software.amazon.awscdk.services.logs.LogGroupProps
import software.amazon.awscdk.services.rds.DatabaseInstance
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine
import software.amazon.awscdk.services.rds.DatabaseInstanceProps
import software.amazon.awscdk.services.servicediscovery.DnsRecordType
import software.amazon.awscdk.services.servicediscovery.PrivateDnsNamespace
import software.amazon.awscdk.services.servicediscovery.PrivateDnsNamespaceProps
import software.amazon.awscdk.services.servicediscovery.ServiceProps
import software.amazon.awscdk.services.ssm.StringParameter

class SonarQubeStack @JvmOverloads constructor(app: App, id: String, props: StackProps? = null) :
    Stack(app, id, props) {
  init {
    // VPC
    val vpc = Vpc(
        this, "vpc", VpcProps.builder()
        .maxAzs(2)
        .cidr("10.0.0.0/16")
        .build()
    )

    // RDS - PostgreSQL
    val databasePassword: String = StringParameter.valueFromLookup(this, "SONAR_JDBC_PASSWORD")
    val rds = DatabaseInstance(this, "sonar-db", DatabaseInstanceProps.builder()
        .instanceIdentifier("sonar-db")
        .masterUsername("sonar")
        .masterUserPassword(SecretValue(databasePassword))
        .engine(DatabaseInstanceEngine.POSTGRES)
        .instanceClass(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
        .vpc(vpc)
        .autoMinorVersionUpgrade(true)
        .databaseName("sonar")
        .multiAz(false)
        .backupRetention(Duration.days(7))
        .port(5432)
        .build()
    )

    val rdsSecurityGroup = SecurityGroup(this, "sonar-db-sg", SecurityGroupProps.builder()
        .securityGroupName("sonar-db-sg")
        .vpc(vpc)
        .build()
    )
    rds.connections.allowDefaultPortFrom(rdsSecurityGroup)

    // ECS Cluster
    val ecsCluster = Cluster(
        this, "morning-code-sonar-cluster", ClusterProps.builder()
        .clusterName("morning-code-sonar-cluster")
        .vpc(vpc)
        .capacity(AddCapacityOptions.builder()
            .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
            .desiredCapacity(1)
            .maxCapacity(1)
            .minCapacity(1)
            .build()
        )
        .build()
    )

    // MEMO: for ElasticSearch error settings.
    // [2]: max virtual memory areas vm.max_map_count [65530] is too low, increase to at least [262144]
    val asg = AutoScalingGroup(this, "auto-scaling-g", AutoScalingGroupProps.builder()
        .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
        .vpc(vpc)
        .machineImage(EcsOptimizedImage.amazonLinux2())
        .vpc(vpc)
        .build()
    )
    asg.addUserData("""
      echo vm.max_map_count=262144 >> /etc/sysctl.conf
      sysctl -w vm.max_map_count=262144
    """.trimIndent())
    ecsCluster.addAutoScalingGroup(asg)

    // ECR
    val containerImage = ContainerImage.fromRegistry("sonarqube:8.2-community")

    // TaskDefinition
    val taskDefinition = Ec2TaskDefinition(this, "morning-code-sonar-task-definition")

    // ECS Log setting
    val awsLogDriver = AwsLogDriver(
        AwsLogDriverProps.builder()
            .logGroup(LogGroup(this, "morning-code-sonar-log", LogGroupProps.builder()
                .logGroupName("morning-code-sonarqube-service")
                .build()
            ))
            .streamPrefix("morning-code-sonarqube-service")
            .build()
    )

    // Container settings
    val sonarDbUrl = "jdbc:postgresql://${rds.dbInstanceEndpointAddress}:${rds.dbInstanceEndpointPort}/sonar"
    val appContainer = taskDefinition.addContainer(
        "morning-code-sonar-container",
        ContainerDefinitionOptions.builder()
            .image(containerImage)
            .cpu(256)
            .memoryLimitMiB(1024)
            .environment(mutableMapOf(
                "SONAR_JDBC_URL" to sonarDbUrl,
                "SONAR_JDBC_USERNAME" to "sonar",
                "SONAR_JDBC_PASSWORD" to databasePassword
            ))
            .logging(awsLogDriver)
            .build()
    )
    appContainer.addPortMappings(PortMapping.builder()
        .containerPort(9000)
        .hostPort(9000)
        .build()
    )
    // MEMO:
    // [1]: max file descriptors [4096] for elasticsearch process is too low, increase to at least [65535]
    appContainer.addUlimits(Ulimit.builder()
        .name(UlimitName.NOFILE).softLimit(65536).hardLimit(65536)
        .build()
    )
    appContainer.addUlimits(Ulimit.builder()
        .name(UlimitName.MEMLOCK).softLimit(-1).hardLimit(-1)
        .build()
    )

    val ec2Service = ApplicationLoadBalancedEc2Service(
        this,
        "ec2-service",
        ApplicationLoadBalancedEc2ServiceProps.builder()
            .cluster(ecsCluster)
            .serviceName("sonarqube-service")
            // TODO: set Route53
            //.domainName("sonar.morningcode.io")
            .desiredCount(1)
            .taskDefinition(taskDefinition)
            // TODO: to be set false
            .publicLoadBalancer(true)
            .build()
    )

    // Cloud Map
    val namespace = PrivateDnsNamespace(
        this,
        "namespace",
        PrivateDnsNamespaceProps.builder()
            .name("sonar.morningcode.io")
            .vpc(vpc)
            .build()
    )

    val service = namespace.createService(
        "service",
        ServiceProps.builder()
            .namespace(namespace)
            .dnsRecordType(DnsRecordType.A_AAAA)
            .dnsTtl(Duration.seconds(30))
            .loadBalancer(true)
            .build()
    )

    service.registerLoadBalancer("load-balancer", ec2Service.loadBalancer)
  }
}
