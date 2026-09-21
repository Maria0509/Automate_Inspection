/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package Libraries;

import Utilities.YoloOnnxDetector;
import coppelia.CharWA;
import coppelia.IntW;
import coppelia.remoteApi;
import jade.core.Agent;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.File;

/**
 *
 * @author Ricardo Silva Peres <ricardo.peres@uninova.pt>
 */
public class SimResourceLibrary implements IResource {
    private static final String INSPECTION_IMAGES_DIR_PROPERTY = "inspection.images.dir";
    private static final String INSPECTION_MODEL_PATH_PROPERTY = "inspection.model.path";

    @Override
    public String getLastInspectionResponse() {
        return lastInspectionResponse;
    }
    private String lastInspectionResponse = "";
    public remoteApi sim;
    public int clientID = -1;
    Agent myAgent;
    private YoloOnnxDetector yoloDetector;
    final long timeout = Utilities.Constants.SIM_RESOURCE_TIMEOUT_MS;
    final long glueTimeout = Utilities.Constants.SIM_GLUE_TIMEOUT_MS;
    final long minSkillObservationMs = 200;
    
    @Override
    public void init(Agent a) {
        this.myAgent = a;
        sim = new remoteApi();
        int port = 0;
        switch(myAgent.getLocalName()){
            case "GlueStation1": port=19997; break;
            case "GlueStation2": port=19998; break;
            case "QualityControlStation1": port=19999; break;
            case "QualityControlStation2": port=20000; break;
            case "Operator": port=20001; break;
        }
        clientID = sim.simxStart("127.0.0.1", port, true, true, 5000, 5);        
        System.out.println("[" + myAgent.getLocalName() +"] port=" + port +"clientID=" +clientID);
        if (clientID != -1) {
            System.out.println(this.myAgent.getAID().getLocalName() + " initialized communication with the simulation.");            
        }else{
            System.out.println("["+myAgent.getLocalName() + "] FAILED to connect on port" + port);
        }
    }

    @Override
    public String[] getSkills() {
        String[] skills;
        switch (myAgent.getLocalName()) {
            case "GlueStation1":
                skills = new String[2];
                skills[0] = Utilities.Constants.SK_GLUE_TYPE_A;
                skills[1] = Utilities.Constants.SK_GLUE_TYPE_B;
                return skills;
            case "GlueStation2":
                skills = new String[2];
                skills[0] = Utilities.Constants.SK_GLUE_TYPE_A;
                skills[1] = Utilities.Constants.SK_GLUE_TYPE_C;
                return skills;
            case "QualityControlStation1":
                skills = new String[1];
                skills[0] = Utilities.Constants.SK_QUALITY_CHECK;
                return skills;
            case "QualityControlStation2":
                skills = new String[1];
                skills[0] = Utilities.Constants.SK_QUALITY_CHECK;
                return skills;
            case "Operator":
                skills = new String[3];
                skills[0] = Utilities.Constants.SK_PICK_UP;
                skills[1] = Utilities.Constants.SK_DROP;
                skills[2] = Utilities.Constants.SK_SINK;
                return skills;
        }
        return null;
    }

    private boolean ensureConnection() {
        if (sim == null) {
            sim = new remoteApi();
        }

        if (clientID != -1 && sim.simxGetConnectionId(clientID) != -1) {
            return true;
        }

        int port = 0;
        switch (myAgent.getLocalName()) {
            case "GlueStation1": port = 19997; break;
            case "GlueStation2": port = 19998; break;
            case "QualityControlStation1": port = 19999; break;
            case "QualityControlStation2": port = 20000; break;
            case "Operator": port = 20001; break;
        }

        clientID = sim.simxStart("127.0.0.1", port, true, true, 5000, 5);
        System.out.println("[" + myAgent.getLocalName() + "] reconnected clientID=" + clientID);

        System.out.println("[" + myAgent.getLocalName() + "] trying remote API port=" + port);
        return clientID != -1;
    }

    private boolean isGlueSkill(String skillID) {
        return Utilities.Constants.SK_GLUE_TYPE_A.equals(skillID) ||
                Utilities.Constants.SK_GLUE_TYPE_B.equals(skillID) ||
                Utilities.Constants.SK_GLUE_TYPE_C.equals(skillID);
    }

    private String buildSignalPayload(String productID, String skillID) {
        switch (myAgent.getLocalName()) {
            case "GlueStation1":
            case "GlueStation2":
                return skillID;
            case "Operator":
                return skillID;

            case "QualityControlStation1":
            case "QualityControlStation2":
                return skillID;

            default:
                return skillID;
        }
    }

    private String getCommandSignalName() {
        switch (myAgent.getLocalName()) {
            case "GlueStation1": return "GlueStation1";
            case "GlueStation2": return "GlueStation2";
            case "QualityControlStation1": return "QualityControlStation1";
            case "QualityControlStation2": return "QualityControlStation2";
            case "Operator": return "Operator";
            default: return myAgent.getLocalName();
        }
    }

    private String getStatusSignalName() {
        switch (myAgent.getLocalName()) {
            case "GlueStation1": return "GlueStation1";
            case "GlueStation2": return "GlueStation2";
            case "QualityControlStation1": return "QualityControlStation1";
            case "QualityControlStation2": return "QualityControlStation2";
            case "Operator": return "Operator";
            default: return myAgent.getLocalName();
        }
    }

    private long getSkillTimeoutMs(String skillID) {
        if (isGlueSkill(skillID)) {
            return glueTimeout;
        }
        return timeout;
    }

    @Override
    public boolean executeSkill(String productID, String skillID) {
        System.out.println("[" + myAgent.getLocalName() + "] executeSkill called with product="
                + productID + " skill=" + skillID);

        // INSPECAO DE QUALIDADE VIA YOLO ONNX LOCAL
        if (skillID.equals("sk_q_c")) {
            System.out.println("[" + myAgent.getLocalName() + "] Executing quality inspection for product " + productID);

            String imagePath = waitForInspectionImage(productID);

            if (imagePath == null) {
                imagePath = getTestInspectionImagePath();

                System.out.println("[" + myAgent.getLocalName() + "] Using test inspection image: " + imagePath);

                File imageFile = new File(imagePath);
                if (!imageFile.exists()) {
                    System.out.println("[" + myAgent.getLocalName() + "] No inspection image available at: " + imagePath);
                    lastInspectionResponse = "{\"status\":\"ERROR\",\"message\":\"image not found\"}";
                    return false;
                }
            }

            System.out.println("[" + myAgent.getLocalName() + "] Inspection image path: " + imagePath);

            YoloOnnxDetector.InspectionResult inspectionResult = runLocalInspection(imagePath);
            lastInspectionResponse = toInspectionJson(inspectionResult);

            System.out.println("[" + myAgent.getLocalName() + "] Inspection result: " + lastInspectionResponse);

            if ("ERROR".equals(inspectionResult.status)) {
                return false;
            }

            if ("NOK".equals(inspectionResult.status)) {
                System.out.println("[" + myAgent.getLocalName() + "] Inspection result: NOK");
            } else if ("OK".equals(inspectionResult.status)) {
                System.out.println("[" + myAgent.getLocalName() + "] Inspection result: OK");
            } else {
                System.out.println("[" + myAgent.getLocalName() + "] Inspection result could not be interpreted.");
            }

            return true;
        }

        if (!ensureConnection()) {
            System.out.println("[" + myAgent.getLocalName() + "] no valid remote API connection");
            return false;
        }

        if (skillID.equals(Utilities.Constants.SK_SINK)) {
            System.out.println("[" + myAgent.getLocalName() + "] Sinking product from system.");
        }

        String commandSignal = getCommandSignalName();
        String statusSignal = getStatusSignalName();
        String signalPayload = buildSignalPayload(productID, skillID);
        long skillTimeoutMs = getSkillTimeoutMs(skillID);

        System.out.println("[" + myAgent.getLocalName() + "] commandSignal=" + commandSignal);
        System.out.println("[" + myAgent.getLocalName() + "] statusSignal=" + statusSignal);
        System.out.println("[" + myAgent.getLocalName() + "] signalPayload=" + signalPayload);
        System.out.println("[" + myAgent.getLocalName() + "] skillTimeoutMs=" + skillTimeoutMs);

        int clearBeforeRet = sim.simxClearIntegerSignal(
                clientID,
                statusSignal,
                sim.simx_opmode_blocking
        );
        System.out.println("[" + myAgent.getLocalName() + "] clearBeforeRet=" + clearBeforeRet);

        if (clearBeforeRet == remoteApi.simx_return_initialize_error_flag) {
            System.out.println("[" + myAgent.getLocalName() + "] clear failed, reconnecting...");
            clientID = -1;

            if (!ensureConnection()) {
                return false;
            }

            clearBeforeRet = sim.simxClearIntegerSignal(
                    clientID,
                    statusSignal,
                    sim.simx_opmode_blocking
            );
            System.out.println("[" + myAgent.getLocalName() + "] clearBeforeRet(after reconnect)=" + clearBeforeRet);
        }

        int setRet = sim.simxSetStringSignal(
                clientID,
                commandSignal,
                new CharWA(signalPayload),
                sim.simx_opmode_blocking
        );

        System.out.println("[" + myAgent.getLocalName() + "] simxSetStringSignal ret=" + setRet);
        if (setRet == remoteApi.simx_return_initialize_error_flag) {
            System.out.println("[" + myAgent.getLocalName() + "] executeSkill failed: initialize error");
            return false;
        }
        if (setRet != remoteApi.simx_return_ok && setRet != remoteApi.simx_return_novalue_flag) {
            System.out.println("[" + myAgent.getLocalName() + "] executeSkill failed: setRet=" + setRet);
            return false;
        }

        IntW opRes = new IntW(-1);
        int startRet = sim.simxGetIntegerSignal(
                clientID,
                statusSignal,
                opRes,
                sim.simx_opmode_streaming
        );
        System.out.println("[" + myAgent.getLocalName() + "] startRet=" + startRet + " initial opRes=" + opRes.getValue());

        if (startRet != remoteApi.simx_return_ok &&
                startRet != remoteApi.simx_return_novalue_flag) {
            System.out.println("[" + myAgent.getLocalName() + "] executeSkill failed: startRet=" + startRet);
        }
        if (startRet == remoteApi.simx_return_initialize_error_flag) {
            System.out.println("[" + myAgent.getLocalName() + "] set failed, reconnecting...");
            clientID = -1;

            if (!ensureConnection()) {
                return false;
            }

            setRet = sim.simxSetStringSignal(
                    clientID,
                    commandSignal,
                    new CharWA(signalPayload),
                    sim.simx_opmode_blocking
            );
            System.out.println("[" + myAgent.getLocalName() + "] simxSetStringSignal ret(after reconnect)=" + setRet);
        }

        long startTime = System.currentTimeMillis();
        boolean sawValidState = false;
        boolean sawNonFinalState = false;
        boolean timedOut = true;

        while (System.currentTimeMillis() - startTime < skillTimeoutMs) {
            int getRet = sim.simxGetIntegerSignal(
                    clientID,
                    statusSignal,
                    opRes,
                    sim.simx_opmode_buffer
            );

            System.out.println("[" + myAgent.getLocalName() + "] getRet=" + getRet + " opRes=" + opRes.getValue());

            if (getRet == remoteApi.simx_return_initialize_error_flag) {
                System.out.println("[" + myAgent.getLocalName() + "] executeSkill polling failed: initialize error");
                break;
            }

            if (getRet == remoteApi.simx_return_ok) {
                sawValidState = true;

                if (opRes.getValue() != 1) {
                    sawNonFinalState = true;
                }

                long elapsed = System.currentTimeMillis() - startTime;
                if (opRes.getValue() == 1 && (sawNonFinalState || elapsed >= minSkillObservationMs)) {
                    timedOut = false;
                    break;
                }
            } else if (getRet == remoteApi.simx_return_novalue_flag) {
                // ainda não chegou valor novo; continua à espera
            } else {
                System.out.println("[" + myAgent.getLocalName() + "] executeSkill polling unexpected getRet=" + getRet);
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                Logger.getLogger(SimResourceLibrary.class.getName()).log(Level.SEVERE, null, ex);
                break;
            }
        }

        long totalElapsed = System.currentTimeMillis() - startTime;
        if (timedOut) {
            System.out.println("[" + myAgent.getLocalName() + "] TIMEOUT waiting for opRes after " +
                    totalElapsed + " ms for product=" + productID + " skill=" + skillID);
        }

        int clearAfterRet = sim.simxClearIntegerSignal(clientID, statusSignal, sim.simx_opmode_blocking);
        System.out.println("[" + myAgent.getLocalName() + "] clearAfterRet=" + clearAfterRet);

        System.out.println("[" + myAgent.getLocalName() + "] final opRes=" + opRes.getValue() +
                " elapsedMs=" + totalElapsed);

        return sawValidState &&
                opRes.getValue() == 1 &&
                (sawNonFinalState || totalElapsed >= minSkillObservationMs);
    }

    private synchronized YoloOnnxDetector getYoloDetector() throws Exception {
        if (yoloDetector == null) {
            yoloDetector = new YoloOnnxDetector(getModelPath(), 640, 0.30f);
        }
        return yoloDetector;
    }

    private YoloOnnxDetector.InspectionResult runLocalInspection(String imagePath) {
        try {
            return getYoloDetector().detect(imagePath);
        } catch (Exception e) {
            e.printStackTrace();
            return new YoloOnnxDetector.InspectionResult("ERROR", 0.0f, -1, "onnx inference failed");
        }
    }

    private String toInspectionJson(YoloOnnxDetector.InspectionResult result) {
        return "{\"status\":\"" + result.status + "\","
                + "\"confidence\":" + result.confidence + ","
                + "\"classId\":" + result.classId + ","
                + "\"message\":\"" + escapeJson(result.message) + "\"}";
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String getModelPath() {
        String configuredPath = System.getProperty(INSPECTION_MODEL_PATH_PROPERTY);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Paths.get(configuredPath).toAbsolutePath().normalize().toString();
        }

        Path[] candidates = new Path[] {
                Paths.get(System.getProperty("user.dir"), "models", "best.onnx"),
                Paths.get(System.getProperty("user.dir"), "CPS", "models", "best.onnx")
        };

        for (Path candidate : candidates) {
            if (candidate.toFile().exists()) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }

        return candidates[0].toAbsolutePath().normalize().toString();
    }

    private Path getInspectionImagesDir() {
        String configuredDir = System.getProperty(INSPECTION_IMAGES_DIR_PROPERTY);
        if (configuredDir != null && !configuredDir.isBlank()) {
            return Paths.get(configuredDir).toAbsolutePath().normalize();
        }

        Path[] candidates = new Path[] {
                Paths.get(System.getProperty("user.dir"), "inspection_images"),
                Paths.get(System.getProperty("user.dir"), "CPS", "inspection_images")
        };

        for (Path candidate : candidates) {
            if (candidate.toFile().exists()) {
                return candidate.toAbsolutePath().normalize();
            }
        }

        return candidates[0].toAbsolutePath().normalize();
    }

    private String getProductInspectionImagePath(String productID) {
        return getInspectionImagesDir().resolve(productID + ".jpg").toString();
    }

    private String getTestInspectionImagePath() {
        Path inspectionDir = getInspectionImagesDir();
        String[] candidateNames = getTestImageCandidates(myAgent.getLocalName());

        for (String candidateName : candidateNames) {
            Path candidatePath = inspectionDir.resolve(candidateName);
            File candidateFile = candidatePath.toFile();
            if (candidateFile.exists() && candidateFile.length() > 0) {
                return candidatePath.toString();
            }
        }

        return inspectionDir.resolve(candidateNames[0]).toString();
    }

    private String[] getTestImageCandidates(String stationName) {
        switch (stationName) {
            case "QualityControlStation1":
                return new String[]{"QualityControlStation1.jpg", "Qualitycontrol1.jpg"};
            case "QualityControlStation2":
                return new String[]{"QualityControlStation2.jpg", "Qualitycontrol2.jpg"};
            default:
                return new String[]{stationName + ".jpg"};
        }
    }

    private String waitForInspectionImage(String productID) {
        String imagePath = getProductInspectionImagePath(productID);

        File imageFile = new File(imagePath);

        long startTime = System.currentTimeMillis();
        long timeoutMs = 10000; // espera até 10 segundos

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (imageFile.exists() && imageFile.length() > 0) {
                System.out.println("[" + myAgent.getLocalName() + "] Inspection image found: " + imagePath);
                return imagePath;
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        System.out.println("[" + myAgent.getLocalName() + "] Timeout waiting for inspection image: " + imagePath);
        return null;
    }

    private String getInspectionImagePath(String productID) {
        return getProductInspectionImagePath(productID);
    }

    @Override
    public boolean launchProduct(String productID) {
        return true;
    }

    @Override
    public boolean finishProduct(String productID) {
        return true;
    }

}
