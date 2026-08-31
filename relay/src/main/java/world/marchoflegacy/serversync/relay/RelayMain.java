package world.marchoflegacy.serversync.relay;

public final class RelayMain {
    public static void main(String[] args) {
        RelayState state = new RelayState("relay-local");
        System.out.println("ServerSync relay started: " + state.relayId());
        System.out.println("Prototype mode: in-memory arbitration only.");
    }
}
