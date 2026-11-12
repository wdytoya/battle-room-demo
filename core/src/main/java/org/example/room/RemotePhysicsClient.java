package org.example.room;

import com.google.gson.JsonObject;
import org.example.config.ClusterConfig;
import org.example.internal.InternalMessage;
import org.example.internal.InternalOps;
import org.example.internal.InternalRpcClient;
import org.example.model.Hand;
import org.example.model.Thrust;
import org.example.spi.ServiceRoles;

import java.util.function.Consumer;

/** Logic → Physics RPC client. */
public final class RemotePhysicsClient implements AutoCloseable {

    private final InternalRpcClient client;

    public RemotePhysicsClient(Consumer<JsonObject> onTickResult) throws Exception {
        this.client = new InternalRpcClient("logic->" + ServiceRoles.PHYSICS,
                ClusterConfig.PHYSICS_HOST, ClusterConfig.PHYSICS_RPC_PORT);
        this.client.setPushHandler((ctx, msg) -> {
            if (InternalOps.PHYS_TICK_RESULT.equals(msg.getOp())) {
                onTickResult.accept(msg.getPayload());
            }
        });
        this.client.connect();
        JsonObject hello = new JsonObject();
        hello.addProperty("from", ServiceRoles.LOGIC);
        client.request(InternalOps.SYS_HELLO, hello, 5000);
        System.out.println("[logic] connected to physics "
                + ClusterConfig.PHYSICS_HOST + ":" + ClusterConfig.PHYSICS_RPC_PORT);
    }

    public void createRoom(BattleRoom room) throws Exception {
        client.request(InternalOps.PHYS_ROOM_CREATE, PhysicsTickCodec.roomCreate(room), 3000);
    }

    public void destroyRoom(String roomId) {
        try {
            JsonObject p = new JsonObject();
            p.addProperty("roomId", roomId);
            client.request(InternalOps.PHYS_ROOM_DESTROY, p, 3000);
        } catch (Exception e) {
            System.err.println("[logic] phys destroy failed: " + e.getMessage());
        }
    }

    public void inputHand(String roomId, String username, Hand hand) {
        JsonObject p = new JsonObject();
        p.addProperty("roomId", roomId);
        p.addProperty("username", username);
        p.addProperty("hand", hand.name());
        client.send(InternalMessage.of(InternalOps.PHYS_INPUT_HAND, null, ServiceRoles.LOGIC, p));
    }

    public void inputThrust(String roomId, String username, Thrust thrust) {
        JsonObject p = new JsonObject();
        p.addProperty("roomId", roomId);
        p.addProperty("username", username);
        p.addProperty("thrust", thrust.name());
        client.send(InternalMessage.of(InternalOps.PHYS_INPUT_THRUST, null, ServiceRoles.LOGIC, p));
    }

    @Override
    public void close() {
        client.close();
    }
}
