package com.ss.kestrel;

import backtype.storm.generated.Nimbus;
import com.ss.kestrel.thrift.Item;
import com.ss.kestrel.thrift.Kestrel;
import com.ss.kestrel.thrift.QueueInfo;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class KestrelThriftClient {
    Kestrel.Client client = null;
    TTransport transport = null;

    public KestrelThriftClient(String hostname, int port)
            throws TException {

        transport = new TFramedTransport(new TSocket(hostname, port));
        TProtocol proto = new TBinaryProtocol(transport);
        client = new Kestrel.Client(proto);
        transport.open();
    }

    public void close() {
        transport.close();
        transport = null;
        client = null;
    }

    public QueueInfo peek(String queue_name) throws TException {
        return client.peek(queue_name);
    }

    public void delete_queue(String queue_name) throws TException {
        client.delete_queue(queue_name);
    }

    public String get_version() throws TException {
        return client.get_version();
    }

    public int put(String queue_name, List<ByteBuffer> items, int expiration_msec) throws TException {
        return client.put(queue_name, items, expiration_msec);
    }

    public void put(String queue_name, String item, int expiration_msec) throws TException {
        List<ByteBuffer> toPut = new ArrayList<ByteBuffer>();
        try {
            toPut.add(ByteBuffer.wrap(item.getBytes("UTF-8")));
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        put(queue_name, toPut, expiration_msec);
    }

    public List<Item> get(String queue_name, int max_items, int timeout_msec, int auto_abort_msec) throws TException {
        return client.get(queue_name, max_items, timeout_msec, auto_abort_msec);
    }

    public int confirm(String queue_name, Set<Long> ids) throws TException {
        return client.confirm(queue_name, ids);
    }

    public int abort(String queue_name, Set<Long> ids) throws TException {
        return client.abort(queue_name, ids);
    }

    public void flush_queue(String queue_name) throws TException {
        client.flush_queue(queue_name);
    }

    public void flush_all_queues() throws TException {
        client.flush_all_queues();
    }

    public static void main(String[] args) {
        try {
            List<ByteBuffer> messages = new ArrayList<ByteBuffer>();
            String s = "";
            for (int i = 0; i< 1000; i++) {
                s += "0";
            }
            s = System.currentTimeMillis() + "\r\n" + s;
            KestrelThriftClient kc = new KestrelThriftClient("localhost", 2229);

            for (int i = 0 ; i < 100; i++) {
                messages.add(ByteBuffer.wrap(s.getBytes()));
            }

//            for (int i= 0; i < 10; i++) {
//                kc.put("aaaaaa", s, 30000);
//            }
           // kc.put("aaaaaa", messages, 30000);

            while (true) {
                List<Item> item = kc.get("send_0", 100, 0, 0);
                if (item != null) {
                    List<ByteBuffer> send = new ArrayList<ByteBuffer>();
                    for (Item it : item) {
                        byte []b = it.get_data();
                        ByteBuffer byteBuffer = ByteBuffer.wrap(b);
                        String s1 = new String(b);
                        System.out.println(s1);
                        send.add(byteBuffer);
                    }
                    kc.put("recv_0", send, 30000);
                }
            }
            //kc.close();
        } catch (TException e) {
            e.printStackTrace();
        }
    }
}
