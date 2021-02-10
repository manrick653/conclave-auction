package com.r3.conclave.sample.auction.client;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Output;
import com.r3.conclave.common.EnclaveInstanceInfo;
import com.r3.conclave.mail.Curve25519KeyPairGenerator;
import com.r3.conclave.mail.EnclaveMail;
import com.r3.conclave.mail.MutableMail;
import com.r3.conclave.sample.auction.common.Message;
import com.r3.conclave.sample.auction.common.MessageSerializer;
import com.r3.conclave.shaded.kotlin.Pair;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyPair;
import java.util.EnumSet;
import java.util.UUID;

public class AuctionClient {

    public static void main(String[] args) throws Exception{
        String messageType = args[0];
        Pair<DataInputStream, DataOutputStream> streams = establishConnection();
        DataInputStream fromHost = streams.getFirst();
        DataOutputStream toHost = streams.getSecond();

        EnclaveInstanceInfo attestation = getAttestation(fromHost);
        int bid = getUserBidInput(args);
        Output serializedOutput = serializeMessage(messageType, bid);
        KeyPair myKey = new Curve25519KeyPairGenerator().generateKeyPair();
        byte [] encryptedMail = createEncryptedMail(myKey, attestation, serializedOutput);

        System.out.println("Sending the encrypted mail to the host.");
        toHost.writeInt(encryptedMail.length);
        toHost.write(encryptedMail);

        // Enclave will mail us back.
        byte[] encryptedReply = new byte[fromHost.readInt()];
        System.out.println("Reading reply mail of length " + encryptedReply.length + " bytes.");
        fromHost.readFully(encryptedReply);
        EnclaveMail reply = attestation.decryptMail(encryptedReply, myKey.getPrivate());
        System.out.println("Enclave gave us the answer '" + new String(reply.getBodyAsBytes()) + "'");

        toHost.close();
        fromHost.close();
    }

    private static byte[] createEncryptedMail(KeyPair myKey, EnclaveInstanceInfo attestation, Output serializedOutput){
        MutableMail mail = attestation.createMail(serializedOutput.getBuffer());
        mail.setPrivateKey(myKey.getPrivate());
        mail.setTopic(UUID.randomUUID().toString());
        byte[] encryptedMail = mail.encrypt();
        return encryptedMail;
    }

    private static Output serializeMessage(String messageType, int bid){
        Message message = new Message(messageType, bid);
        Output output = new Output(new ByteArrayOutputStream());
        Kryo kryo = new Kryo();
        kryo.register(Message.class, new MessageSerializer());
        kryo.writeObject(output, message);
        output.close();
        return output;
    }

    private static int getUserBidInput(String args[]) throws Exception{
        if(args.length > 0 && args[0].equals("BID")) {
            System.out.println("Please enter your Bid Amount");
            System.out.println();
            System.out.println("---------------------------");
            System.out.println("Bid Amount: ");
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(System.in));
            String bidAmount = reader.readLine();
            return Integer.valueOf(bidAmount);
        }
        return 0;
    }

    private static Pair<DataInputStream, DataOutputStream> establishConnection() throws Exception{
        DataInputStream fromHost;
        DataOutputStream toHost;
        while (true) {
            try {
                System.out.println("Attempting to connect to Host at localhost:5051");
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5051), 10000);
                fromHost = new DataInputStream(socket.getInputStream());
                toHost = new DataOutputStream(socket.getOutputStream());
                break;
            } catch (Exception e) {
                System.err.println("Retrying: " + e.getMessage());
                Thread.sleep(2000);
            }
        }
        return new Pair<>(fromHost, toHost);
    }

    private static EnclaveInstanceInfo getAttestation(DataInputStream fromHost) throws Exception{
        byte[] attestationBytes = new byte[fromHost.readInt()];
        fromHost.readFully(attestationBytes);
        EnclaveInstanceInfo attestation = EnclaveInstanceInfo.deserialize(attestationBytes);

        System.out.println("Attestation Info received:  " + attestation);
        return attestation;
    }
}