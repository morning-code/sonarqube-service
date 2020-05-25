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
        .enableDnsHostnames(true)
        .enableDnsSupport(true)
        .maxAzs(2)
        .cidr("10.0.0.0/16")
        .subnetConfiguration(
            mutableListOf(
                SubnetConfiguration.builder()
                    .cidrMask(18)
                    .name("Public")
                    .subnetType(SubnetType.PUBLIC)
                    .build(),
                SubnetConfiguration.builder()
                    .cidrMask(18)
                    .name("Private")
                    .subnetType(SubnetType.PRIVATE)
                    .build()
            )
        )
        .build()
    )

    // Security Group settings
    /*
    val albSecurityGroup = SecurityGroup(this, "sonar-alb-sg", SecurityGroupProps.builder()
        .securityGroupName("sonar-alb-sg")
        .description("security group for SonarQube ALB")
        .allowAllOutbound(true)
        .vpc(vpc)
        .build()
    )
    albSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(80), "Http Port", false)

    val ecsSecurityGroup = SecurityGroup(this, "sonar-sg", SecurityGroupProps.builder()
        .securityGroupName("sonar-sg")
        .description("security group for SonarQube ECS")
        .allowAllOutbound(true)
        .vpc(vpc)
        .build()
    )
    ecsSecurityGroup.connections.allowFrom(
        Connections(ConnectionsProps.builder()
            .securityGroups(listOf(albSecurityGroup))
            .build()
        ),
        Port.tcp(80),
        "ECS Port"
    )

    val rdsSecurityGroup = SecurityGroup(this, "sonar-db-sg", SecurityGroupProps.builder()
        .securityGroupName("sonar-db-sg")
        .description("security group for SonarQube RDS")
        .vpc(vpc)
        .allowAllOutbound(true)
        .build()
    )
    rdsSecurityGroup.connections.allowFrom(
        Connections(ConnectionsProps.builder()
            .securityGroups(listOf(ecsSecurityGroup))
            .build()
        ),
        Port.tcp(5432),
        "DB Port"
    )

     */

    val rdsSecurityGroup = SecurityGroup(this, "sonar-db-sg", SecurityGroupProps.builder()
        .securityGroupName("sonar-db-sg")
        .description("security group for SonarQube RDS")
        .vpc(vpc)
        .allowAllOutbound(true)
        .build()
    )

    rdsSecurityGroup.addIngressRule(Peer.anyIpv4(), Port.tcp(5432), "SonarQube RDS Port", false)

    /*
    rdsSecurityGroup.connections.allowFrom(
        Connections(ConnectionsProps.builder()
            .build()
        ),
        Port.tcp(5432),
        "SonarQube RDS Port"
    )
     */

    // RDS - PostgreSQL
    val databasePassword: String = StringParameter.valueFromLookup(this, "SONAR_JDBC_PASSWORD")

    val rds = DatabaseInstance(this, "sonar-db", DatabaseInstanceProps.builder()
        .instanceIdentifier("sonar-db")
        //.timezone("Asia/Tokyo")
        .masterUsername("sonar")
        .masterUserPassword(SecretValue(databasePassword))
        .engine(DatabaseInstanceEngine.POSTGRES)
        .instanceClass(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
        .vpc(vpc)
        .securityGroups(listOf(rdsSecurityGroup))
        .enablePerformanceInsights(true)
        .autoMinorVersionUpgrade(true)
        .databaseName("sonar")
        .multiAz(false)
        .backupRetention(Duration.days(7))
        .port(5432)
        .build()
    )

    rds.connections.allowDefaultPortFrom(rdsSecurityGroup)

    // ECS Cluster
    val ecsCluster = Cluster(
        this, "morning-code-sonar-cluster", ClusterProps.builder()
        .clusterName("morning-code-sonar-cluster")
        .vpc(vpc)
        .build()
    )

    // MEMO: for ElasticSearch error settings.
    // [2]: max virtual memory areas vm.max_map_count [65530] is too low, increase to at least [262144]
    val userData = UserData.forLinux()
    userData.addCommands(
        "echo 'Adding User Commands...'",
        "echo vm.max_map_count=262144 >> /etc/sysctl.conf",
        "sysctl -w vm.max_map_count=262144",
        "echo installing Amazon Inspector Agent...",
        "curl -O https://d1wk0tztpsntt1.cloudfront.net/linux/latest/install",
        "sudo bash install",
        "echo 'finish!!'"
    )

    // EC2 - AutoScalingGroup
    val asg = AutoScalingGroup(this, "auto-scaling-g", AutoScalingGroupProps.builder()
        .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MEDIUM))
        // 意味ないけど。。。料金高いときにインスタンスをシャットダウンできるよう
        .desiredCapacity(0)
        .maxCapacity(1)
        .minCapacity(0)
        .vpc(vpc)
        .machineImage(EcsOptimizedImage.amazonLinux2())
        .keyName("sonar-key")
        .userData(userData)
        .build()
    )
    //asg.addSecurityGroup(ecsSecurityGroup)

    ecsCluster.addAutoScalingGroup(asg)

    // Container
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
            .memoryLimitMiB(2048)
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
        .hostPort(80)
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
            // TODO: to be set Route53
            //.domainName("sonar.morningcode.io")
            .desiredCount(1)
            .taskDefinition(taskDefinition)
            // TODO: to be set false
            .publicLoadBalancer(true)
            .build()
    )

    //ec2Service.loadBalancer.connections.addSecurityGroup(albSecurityGroup)

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
            .name("sonarqube-service")
            .namespace(namespace)
            .dnsRecordType(DnsRecordType.A_AAAA)
            .dnsTtl(Duration.seconds(30))
            .loadBalancer(true)
            .build()
    )

    service.registerLoadBalancer("load-balancer", ec2Service.loadBalancer)

    // tagging
    Tag.add(this, "ServiceName", "sonarqube")
  }
}
