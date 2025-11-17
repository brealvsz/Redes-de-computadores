
import protocol.Checksum;
import protocol.Packet;
import protocol.PacketSerializer;
import protocol.PacketType;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.file.Files;
import java.util.Arrays;

public class Server {
    private static final int BUFFER_SIZE = 2048;
    private static final int SERVER_PORT = 8080;

    public static void main (String[] args){
        try(DatagramSocket mainSocket = new DatagramSocket(SERVER_PORT)){
            System.out.println("Servidor UDP iniciado na porta: " + SERVER_PORT);

            while(true){
                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket initialRequest = new DatagramPacket(buffer, buffer.length);
                mainSocket.receive(initialRequest);

                new ClientHandler(initialRequest).start();

            }
        } catch (SocketException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}

class ClientHandler extends Thread {
    private final DatagramPacket initialRequest;

    public ClientHandler(DatagramPacket requestPacket) {
        this.initialRequest = requestPacket;
    }

    @Override
    public void run() {
        //criando um novo socket somente para este cliente
        try (DatagramSocket transferSocket = new DatagramSocket()) {
            InetAddress clientAddress = initialRequest.getAddress();
            int clientPort = initialRequest.getPort();

            String requestString = new String(initialRequest.getData(), 0, initialRequest.getLength());
            System.out.println("Requisição recebida de " + clientAddress.getHostAddress() + ": " + requestString);

            if (requestString.startsWith("GET ")) {
                String filename = requestString.substring(4).trim();
                handleFileRequest(filename, clientAddress, clientPort, transferSocket);
            } else {
                sendError("Requisição mal formatada.", clientAddress, clientPort, transferSocket);
            }

        } catch (SocketException e) {
            System.err.println("Não foi possível criar socket para o handler: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Erro de IO no handler: " + e.getMessage());
        }
    }

    //se arquivo existir e puder ser lido, envia para ser segmentado e enviado ao cliente
    private void handleFileRequest(String filename, InetAddress clientAddress, int clientPort, DatagramSocket socket) {
        File file = new File("src/files/" + filename);
        if (!file.exists() || !file.isFile()) {
            sendError("Arquivo não encontrado: " + filename, clientAddress, clientPort, socket);
            return;
        }

        try {
            byte[] fileData = Files.readAllBytes(file.toPath());
            sendFileInSegments(fileData, clientAddress, clientPort, socket);
        } catch (IOException e) {
            sendError("Erro ao ler o arquivo: " + e.getMessage(), clientAddress, clientPort, socket);
        }
    }

    //transeferencia, usando stop and wait
    private void sendFileInSegments(byte[] fileData, InetAddress clientAddress, int clientPort, DatagramSocket socket) {
        try {
            int segmentSize = 950;
            int totalSegments = (int) Math.ceil((double) fileData.length / segmentSize);

            int currentSegment = 0;
            while (currentSegment < totalSegments) {
                //preparando o pacote p o segmento atual
                int start = currentSegment * segmentSize;
                int end = Math.min(start + segmentSize, fileData.length);
                byte[] segmentData = Arrays.copyOfRange(fileData, start, end);

                Packet packetToSend = new Packet(currentSegment, segmentData);
                long checksumValue = Checksum.calculate(segmentData);
                packetToSend.setChecksum(checksumValue);

                byte[] sendData = PacketSerializer.serialize(packetToSend);
                DatagramPacket datagramToSend = new DatagramPacket(sendData, sendData.length, clientAddress, clientPort);

                boolean ackReceived = false;
                int retries = 0;

                //loop de tentativas
                while (!ackReceived && retries < 8) {
                    try {
                        //envia o pacote de dados
                        socket.send(datagramToSend);
                        System.out.println("Enviado segmento " + currentSegment + " (tentativa " + (retries + 1) + ")");

                        //timeout p esperar pelo ACK
                        socket.setSoTimeout(2000);

                        //espera pelo ACK
                        byte[] ackBuffer = new byte[1024];
                        DatagramPacket ackDatagram = new DatagramPacket(ackBuffer, ackBuffer.length);
                        socket.receive(ackDatagram);

                        Packet ackPacket = PacketSerializer.deserialize(ackDatagram.getData());

                        //verifica se é o ACK correto
                        if (ackPacket.getType() == PacketType.ACK && ackPacket.getSequenceNumber() == currentSegment) {
                            System.out.println("ACK para segmento " + currentSegment + " recebido.");
                            ackReceived = true;
                            currentSegment++; //prox segmento
                        } else {
                            System.out.println("ACK incorreto recebido. Esperado: " + currentSegment + ", Recebido: " + ackPacket.getSequenceNumber() + ". Ignorando.");
                        }

                    } catch (SocketTimeoutException e) {
                        System.err.println("Timeout! Reenviando segmento " + currentSegment);
                        retries++;
                    }
                }

                if (!ackReceived) {
                    System.err.println("Não foi possível entregar o segmento " + currentSegment + " após " + retries + " tentativas. Abortando.");
                    break;
                }
            }

            //todos os segmentos enviados, envia o EOT
            if (currentSegment == totalSegments) {

                socket.setSoTimeout(0);
                Packet eotPacket = new Packet(PacketType.END_OF_FILE, totalSegments);
                byte[] eotData = PacketSerializer.serialize(eotPacket);
                DatagramPacket eotDatagram = new DatagramPacket(eotData, eotData.length, clientAddress, clientPort);
                socket.send(eotDatagram);
                System.out.println("Marca de fim de arquivo enviada.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                socket.setSoTimeout(0);
            } catch (SocketException e) {
                e.printStackTrace();
            }
        }
    }

    private void sendError(String message, InetAddress clientAddress, int clientPort, DatagramSocket socket) {
        try {
            System.err.println("Enviando erro para o cliente: " + message);
            Packet errorPacket = new Packet(PacketType.ERROR, -1);
            byte[] errorBytes = PacketSerializer.serialize(errorPacket);
            DatagramPacket datagram = new DatagramPacket(errorBytes, errorBytes.length, clientAddress, clientPort);
            socket.send(datagram);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}

//rodar -> java -cp out/production/src Server