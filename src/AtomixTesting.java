import io.atomix.cluster.Node;
import io.atomix.cluster.discovery.BootstrapDiscoveryProvider;
import io.atomix.core.Atomix;
import io.atomix.protocols.raft.partition.RaftPartitionGroup;

public class AtomixTesting {

    public static void main(String args[]){
        System.out.println("hello world");

        Atomix atomix = Atomix.builder()
                .withMemberId("member1")
                .withMembershipProvider(BootstrapDiscoveryProvider.builder()
                        .withNodes(
                                Node.builder()
                                        .withId("member1")
                                        .withAddress("10.192.19.181:5679")
                                        .build(),
                                Node.builder()
                                        .withId("member2")
                                        .withAddress("10.192.19.182:5679")
                                        .build(),
                                Node.builder()
                                        .withId("member3")
                                        .withAddress("10.192.19.183:5679")
                                        .build())
                        .build())
                .withManagementGroup(RaftPartitionGroup.builder("system")
                        .withNumPartitions(1)
                        .withMembers("member1", "member2", "member3")
                        .build())
                .withPartitionGroups(RaftPartitionGroup.builder("raft")
                        .withPartitionSize(3)
                        .withNumPartitions(3)
                        .withMembers("member1", "member2", "member3")
                        .build())
                .build();


    }
}
