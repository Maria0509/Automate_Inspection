/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package Libraries;

import coppelia.CharWA;
import coppelia.IntW;
import coppelia.remoteApi;
import jade.core.Agent;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Ricardo Silva Peres <ricardo.peres@uninova.pt>
 */
public class SimTransportLibrary implements ITransport {

    remoteApi sim;
    int clientID;
    Agent myAgent;
    final long timeout = Utilities.Constants.SIM_TRANSPORT_TIMEOUT_MS;
    final long minMoveObservationMs = 200;

    @Override
    public void init(Agent a) {
        this.myAgent = a;

        this.sim = new remoteApi();
        this.clientID = sim.simxStart("127.0.0.1", 20002, true, true, 5000, 5);
        System.out.println("[SimTransportLibrary] clientID = " + this.clientID);

        if (this.clientID != -1) {
            System.out.println(this.myAgent.getAID().getLocalName() + " initialized communication with the simulation.");            
        }else {
            System.out.println("[SimTransportLibrary] FAILED to connect to simulation on port 20002");
        }
    }

    @Override
    public String[] getSkills() {
        String[] skills = new String[1];
        skills[0] = Utilities.Constants.SK_MOVE;
        return skills;
    }

    @Override
    public boolean executeMove(String origin, String destination, String productID) {
        if (clientID == -1) {
            System.out.println("[SimTransportLibrary] invalid clientID");
            return false;
        }

        String movePayload = productID + "#" + origin + "#" + destination;
        System.out.println("[SimTransportLibrary] executeMove called: " + productID + " " + origin + " -> " + destination);
        System.out.println("[SimTransportLibrary] movePayload=" + movePayload);

        int clearBeforeRet = sim.simxClearIntegerSignal(clientID, "Move", sim.simx_opmode_blocking);
        System.out.println("[SimTransportLibrary] clearBeforeRet=" + clearBeforeRet);

        int setRet = sim.simxSetStringSignal(
                clientID,
                "Move",
                new CharWA(movePayload),
                sim.simx_opmode_oneshot
        );
        System.out.println("[SimTransportLibrary] setRet=" + setRet);
        if (setRet == remoteApi.simx_return_initialize_error_flag) {
            System.out.println("[SimTransportLibrary] Move command failed: initialize error");
            return false;
        }

        IntW opRes = new IntW(-1);

        // iniciar streaming
        int startRet = sim.simxGetIntegerSignal(clientID, "Move", opRes, sim.simx_opmode_streaming);
        System.out.println("[SimTransportLibrary] startRet=" + startRet + " initial opRes=" + opRes.getValue());

        long startTime = System.currentTimeMillis();
        boolean sawNonFinalState = false;
        boolean timedOut = true;

        while (System.currentTimeMillis() - startTime < timeout) {
            int getRet = sim.simxGetIntegerSignal(clientID, "Move", opRes, sim.simx_opmode_buffer);
            System.out.println("[SimTransportLibrary] getRet=" + getRet + " opRes=" + opRes.getValue());

            if (getRet == remoteApi.simx_return_initialize_error_flag) {
                System.out.println("[SimTransportLibrary] Move polling failed: initialize error");
                break;
            }

            if (opRes.getValue() != 1) {
                sawNonFinalState = true;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            if (opRes.getValue() == 1 && (sawNonFinalState || elapsed >= minMoveObservationMs)) {
                timedOut = false;
                break;
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Logger.getLogger(SimTransportLibrary.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        long totalElapsed = System.currentTimeMillis() - startTime;
        if (timedOut) {
            System.out.println("[SimTransportLibrary] TIMEOUT waiting for Move after " +
                    totalElapsed + " ms for payload=" + movePayload);
        }

        sim.simxClearIntegerSignal(clientID, "Move", sim.simx_opmode_oneshot);

        System.out.println("[SimTransportLibrary] final opRes=" + opRes.getValue() +
                " elapsedMs=" + totalElapsed);

        return opRes.getValue() == 1 && (sawNonFinalState || totalElapsed >= minMoveObservationMs);
    }

}
