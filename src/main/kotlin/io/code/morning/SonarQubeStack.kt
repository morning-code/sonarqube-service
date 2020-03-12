package io.code.morning

import software.amazon.awscdk.core.App
import software.amazon.awscdk.core.Duration
import software.amazon.awscdk.core.Stack
import software.amazon.awscdk.core.StackProps
import software.amazon.awscdk.services.ec2.Vpc
import software.amazon.awscdk.services.ec2.VpcProps
import software.amazon.awscdk.services.ecr.IRepository
import software.amazon.awscdk.services.ecr.Repository
import software.amazon.awscdk.services.ecs.*
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateServiceProps
import software.amazon.awscdk.services.servicediscovery.DnsRecordType
import software.amazon.awscdk.services.servicediscovery.PrivateDnsNamespace
import software.amazon.awscdk.services.servicediscovery.PrivateDnsNamespaceProps
import software.amazon.awscdk.services.servicediscovery.ServiceProps

class SonarQubeStack @JvmOverloads constructor(app: App, id: String, props: StackProps? = null) :
    Stack(app, id, props) {
  init {
    // VPC
    val vpc = Vpc(
        this, "morning-code-sonarqube-vpc", VpcProps.builder()
        .maxAzs(2)
        .cidr("10.0.0.0/16")
        .build()
    )

    // ECS Cluster
    val ecsCluster = Cluster(
        this, "morning-code-sonarqube-cluster", ClusterProps.builder()
        .clusterName("morning-code-sonarqube-cluster")
        .vpc(vpc)
        .build()
    )

    // ECR
    val ecrArn: String = this.node.tryGetContext("ecrArn").toString()
    val repository: IRepository = Repository.fromRepositoryArn(this, "morning-code-sonarqube-ecr", ecrArn)
    val containerImage = EcrImage.fromEcrRepository(repository, "latest")

    // TaskDefinition
    val taskDefinition =
        FargateTaskDefinition(
            this, "morning-code-sonarqube-task-definition",
            FargateTaskDefinitionProps.builder()
                .cpu(256)
                .memoryLimitMiB(1024)
                .build()
        )

    // Container settings
    val appContainer = taskDefinition.addContainer(
        "morning-code-sonarqube-container",
        ContainerDefinitionOptions.builder()
            .image(containerImage)
            .build()
    )
    appContainer.addPortMappings(PortMapping.builder().containerPort(9000).build())

    // Fargate
    val fargateService = ApplicationLoadBalancedFargateService(
        this,
        "FargateService",
        ApplicationLoadBalancedFargateServiceProps.builder()
            .cluster(ecsCluster)
            .desiredCount(1)
            .taskDefinition(taskDefinition)
            // TODO: to be set false
            .publicLoadBalancer(true)
            .build()
    )

    // Cloud Map
    val namespace = PrivateDnsNamespace(
        this,
        "NameSpace",
        PrivateDnsNamespaceProps.builder()
            .name("sonarqube.morningcode.io")
            .vpc(vpc)
            .build()
    )

    val service = namespace.createService(
        "Service",
        ServiceProps.builder()
            .namespace(namespace)
            .dnsRecordType(DnsRecordType.A_AAAA)
            .dnsTtl(Duration.seconds(30))
            .loadBalancer(true)
            .build()
    )

    service.registerLoadBalancer("LoadBalancer", fargateService.loadBalancer)

  }
}
