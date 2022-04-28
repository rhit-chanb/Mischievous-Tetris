import io.atomix.cluster.messaging.ClusterEventService;
import io.atomix.core.Atomix;

public class AtomixTesting {

    public static void main(String args[]){
        System.out.println("init");

        Atomix atomix = new Atomix("clientTest2.conf");

        /*
        // stuff from the website (also throws random null pointers)
        Atomix atomix = Atomix.builder()
  .withMemberId("member1")
  .withNodeDiscovery(BootstrapDiscoveryProvider.builder()
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
  */

        atomix.start().join();

        ClusterEventService eventService = atomix.getEventService();

        eventService.subscribe("test", message -> {
          System.out.println("Received message" + message);
          return null;
        });

        eventService.broadcast("test", "Hello World!");
    }
}
