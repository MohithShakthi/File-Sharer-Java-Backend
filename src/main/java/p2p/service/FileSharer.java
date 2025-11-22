package p2p.service;

import p2p.utils.UploadUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;

public class FileSharer {

    private final ConcurrentHashMap<Integer, String> availableFiles;

    public FileSharer(){
        availableFiles = new ConcurrentHashMap<>();
    }

    public int offerFile(String filePath){
        int port;
        do{
            port = UploadUtils.generateCode();
        }while (availableFiles.containsKey(port));
        availableFiles.put(port,filePath);
        return port;
    }

    public void startFileServer(int port){
        String filepath = availableFiles.get(port);
        if(filepath == null) {
            System.out.println("No file is associated with port: "+ port);
            return;
        }
        try(ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Serving file "+ new File(filepath).getName() + " on port "+ port);
            Socket clientSocket = serverSocket.accept();
            System.out.println("Client connection: " + clientSocket.getInetAddress());
            new Thread(new FileSenderHandler(clientSocket,filepath)).start();
        }catch(BindException ex){
            System.err.println("Port "+port+" is already in use");
        }
        catch (IOException ex){
            System.err.println("Error handling file server on port:" + port);
        }
    }

    private  static class FileSenderHandler implements Runnable {

        private final Socket clientSocket;
        private final String filePath;

        public FileSenderHandler(Socket clientSocket, String filePath){
            this.clientSocket = clientSocket;
            this.filePath = filePath;
        }

        @Override
        public void run() {
            try(FileInputStream fis = new FileInputStream(filePath)){
                OutputStream oos = clientSocket.getOutputStream();
                String fileName = new File(filePath).getName();
                String header = "filename: "+fileName+"\n";
                oos.write(header.getBytes());

                byte[] buffer = new byte[4096];
                int byteRead;
                while ((byteRead = fis.read(buffer)) != -1){
                    oos.write(buffer, 0, byteRead);
                }
                oos.flush();
                System.out.println("File "+fileName+" send to "+clientSocket.getInetAddress());
            }catch (Exception e){
                System.err.println("Error sending file to client "+ e.getMessage());
            }finally {
                try {
                    clientSocket.close();
                }catch (Exception e){
                    System.err.println("Error closing socket: " +e.getMessage());
                }
            }
        }
    }
}
