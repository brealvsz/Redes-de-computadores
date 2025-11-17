package protocol;

import java.io.*;

public class PacketSerializer {
    public static byte[] serialize (Packet packet) throws IOException{
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        ObjectOutputStream objectStream = new ObjectOutputStream(byteStream);
        objectStream.writeObject(packet);
        objectStream.flush();
        return byteStream.toByteArray();
    }

    public static Packet deserialize(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
        ObjectInputStream objectStream = new ObjectInputStream(byteStream);
        return (Packet) objectStream.readObject();

    }
}
