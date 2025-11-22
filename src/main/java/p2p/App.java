package p2p;

import p2p.controller.FileController;

public class App {
    public static void main(String[] args) {
        System.out.println("Hello Iam P2P file sharer! :)");
        int port = 8080;
        try{
            FileController fileController = new FileController(port);
            fileController.start();
            System.out.println("P2P server running on port: "+port);
            System.out.println("UI available at http://localhost:3000");
            Runtime.getRuntime().addShutdownHook(
                    new Thread(
                            () -> {
                                System.out.println("Shuting down the server");
                                fileController.stop();
                            }
                    )
            );

            System.out.println("Press enter to stop the server");
            System.in.read(); // server should stop if someone presses enter.
            System.out.println("Stopping server...");
            fileController.stop();
        }catch (Exception e){
            System.err.println("Failed to start the server at port 8080");
            e.printStackTrace();
        }
    }
}
