import protocol.Checksum;
import protocol.Packet;
import protocol.PacketSerializer;
import protocol.PacketType;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StreamCorruptedException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Random;
import java.util.Scanner;
import java.util.TreeMap;

public class Client {

    private static final int BUFFER_SIZE = 2048;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Endereço do servidor e nome do arquivo no formato : @IP:Porta/nome_do_arquivo");
        String input = scanner.nextLine();
        try {
            String[] parts = parseInput(input);
            String serverAddress = parts[0];
            int port = Integer.parseInt(parts[1]);
            String filename = parts[2];

            startFileTransfer(serverAddress, port, filename);

        } catch (IllegalArgumentException e) {
            System.err.println("ERRO: " + e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    //manipula o input do cliente para pegar as informações ip, porta e nome do arquivo
    private static String[] parseInput(String input) throws IllegalArgumentException {

        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("A entrada não pode ser vazia.");
        }

        input = input.trim();

        if (!input.startsWith("@") || !input.contains(":") || !input.contains("/")) {
            throw new IllegalArgumentException("Formato inválido. Formato correto: @IP:Porta/nome_do_arquivo");
        }

        try {
            String withoutAt = input.substring(1);

            int colonIndex = input.indexOf(":");
            int slashIndex = input.indexOf("/");

            String ip = input.substring(1, colonIndex);
            String port = input.substring(colonIndex + 1, slashIndex);
            String filename = input.substring(slashIndex + 1);

            if (ip.isEmpty() || port.isEmpty() || filename.isEmpty()) {
                throw new IllegalArgumentException("IP, porta ou nome do arquivo não especificado");
            }
            Integer.parseInt(port);

            return new String[]{ip, port, filename};
        } catch (Exception e) {
            throw new IllegalArgumentException("Formato invalido");
        }
    }

    //processo de transferencia por parte do cliente
    private static void startFileTransfer(String serverAddress, int port, String filename) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress address = InetAddress.getByName(serverAddress);

            //organiza e envia a requisiçao p server
            String requestMessage = "GET " + filename;
            byte[] requestBytes = requestMessage.getBytes();
            DatagramPacket requestPacket = new DatagramPacket(requestBytes, requestBytes.length, address, port);
            socket.send(requestPacket);
            System.out.println("Requisição enviada para o servidor: " + requestMessage);

            //treeMap para armazenar os segmentos ordenadamente
            TreeMap<Integer, byte[]> receivedSegments = new TreeMap<>();
            int totalSegments = -1;
            boolean eotReceived = false;
            System.out.println("Aguardando resposta do servidor..");
            socket.setSoTimeout(10000);

            //loop principal de recebimento
            while (true) {
                byte[] buffer = new byte[BUFFER_SIZE];
                DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);

                try {
                    socket.receive(receivePacket);
                    Packet packet;
                    try {
                        //deserializar como um objeto Packet
                        packet = PacketSerializer.deserialize(receivePacket.getData());
                    } catch (StreamCorruptedException | ClassNotFoundException e) {
                        //se falhar, tenta ler como uma String de erro
                        String errorMsg = new String(receivePacket.getData(), 0, receivePacket.getLength());
                        System.err.println("Recebido pacote não-serializado, possivelmente um erro: " + errorMsg);
                        continue; //ignora e aguarda o prox
                    }

                    //se a deserialização foi bem-sucedida, processa o pacote com base no tipo dele
                    if (packet.getType() == PacketType.DATA) {
                        handleDataPacket(packet, receivedSegments, socket, receivePacket.getAddress(), receivePacket.getPort());
                    } else if (packet.getType() == PacketType.END_OF_FILE) {
                        totalSegments = packet.getSequenceNumber();
                        eotReceived = true;
                        System.out.println("Marca de fim de arquivo recebida. Total de segmentos: " + totalSegments);
                    } else if (packet.getType() == PacketType.ERROR) {
                        System.err.println("Servidor retornou um erro. Abortando.");
                        return;
                    }

                    if (eotReceived && receivedSegments.size() == totalSegments) {
                        System.out.println("Todos os pacotes recebidos.");
                        break;
                    }

                } catch (SocketTimeoutException e) {
                    System.err.println("Timeout! O servidor não respondeu a tempo. Abortando.");
                    break;
                }
            }

        //montagem do arquivo
        if (totalSegments != -1 && receivedSegments.size() == totalSegments) {
            System.out.println("Todos os segmentos recebidos. Montando arquivo...");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (byte[] segmentData : receivedSegments.values()) {
                baos.write(segmentData);
            }

            String outputFilename = "recebido_" + filename;
            try (FileOutputStream fos = new FileOutputStream(outputFilename)) {
                fos.write(baos.toByteArray());
                System.out.println("Arquivo salvo com sucesso como: " + outputFilename);
            }
        } else {
            System.err.println("Nem todos os segmentos foram recebidos. Falha ao montar arquivo");
            System.err.println("Recebidos " + receivedSegments.size() + " de " + totalSegments);
        }

        }

    }

    //processa os pacotes tipo DATA, verifica integridade, simulador de perdas e envia ACK
    private static void handleDataPacket(Packet packet, TreeMap<Integer, byte[]> receivedSegments, DatagramSocket socket, InetAddress serverAddress, int serverPort) throws IOException {
        long receivedChecksum = packet.getChecksum();
        long calculatedChecksum = Checksum.calculate(packet.getData());
        int seqNum = packet.getSequenceNumber();

        if (receivedChecksum != calculatedChecksum) {
            System.err.println("Checksum inválido para o segmento " + seqNum + ". Descartando.");
            return; //retorna sem enviar ACK
        }

        //teste de nao recebimento de pacote
/*
        Random random = new Random();
        if (random.nextInt(100) < 20) {
            System.err.println("<<<<< PERDA SIMULADA: Pacote " + seqNum + " recebido, mas ACK não será enviado. >>>>>");
            return; //retorna sem enviar ACK
        }
*/
        if (!receivedSegments.containsKey(seqNum)) {
            receivedSegments.put(seqNum, packet.getData());
            System.out.println("Recebido segmento " + seqNum + " (OK)");
        } else {
            System.out.println("Recebido segmento duplicado " + seqNum + " (OK)");
        }

        Packet ackPacket = new Packet(PacketType.ACK, seqNum);
        byte[] ackBytes = PacketSerializer.serialize(ackPacket);
        DatagramPacket ackDatagram = new DatagramPacket(ackBytes, ackBytes.length, serverAddress, serverPort);
        socket.send(ackDatagram);
        System.out.println("--> ACK para segmento " + seqNum + " enviado.");
    }
}

//rodar -> java -cp out/production/src Client