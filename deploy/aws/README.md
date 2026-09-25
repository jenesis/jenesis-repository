AWS
===

Deploys Jenesis Repository on **ECS Fargate** behind an Application Load Balancer, over an **S3** bucket the stack
provisions (or one it is given): `JENREG_STORE=s3`. The store credential is the task role, found through the
standard AWS credential chain, so no key is set anywhere.

    aws cloudformation deploy --stack-name jenesis-repository \
      --capabilities CAPABILITY_IAM \
      --template-file cloudformation.yaml \
      --parameter-overrides VpcId=vpc-xxxx SubnetIds=subnet-aaaa,subnet-bbbb \
        BootstrapKey=jenk_... AdminKey=...
    # the stack output `Endpoint` is the repository; check <endpoint>/actuator/health, and open <endpoint> for the console

| Parameter | Default | |
|---|---|---|
| `Image` | `docker.io/jenesisbuild/jenesis-repository:latest` | Fargate pulls from Docker Hub directly |
| `BootstrapKey`, `AdminKey` | empty | the two starter credentials, each kept in Secrets Manager |
| `EnvironmentFile` | empty | S3 object ARN of a file of further `JENREG_*=value` lines |
| `BucketName` | empty | an existing bucket; empty creates one |
| `VpcId`, `SubnetIds` | | two or more public subnets, for the ALB and the tasks |
| `DesiredCount`, `Cpu`, `Memory` | `1`, `512`, `2048` | the image sets no `-Xmx`, so about a quarter of the memory becomes heap |

CloudFormation cannot iterate over a map, which is why the settings beyond the two credentials come as an
[environment file](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/use-environment-file.html) rather
than a parameter each; the execution role is granted exactly that one object.

**The listener is plain HTTP on port 80**, so credentials cross the network in the clear until a certificate is
added: an HTTPS listener with an ACM certificate on the load balancer, forwarding to the same target group.
