package lu.fisch.canze.devices;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;

import lu.fisch.canze.activities.MainActivity;
import lu.fisch.canze.actors.Field;
import lu.fisch.canze.actors.Message;
import lu.fisch.canze.actors.Utils;

/**
 * Created by robertfisch on 07.09.2015.
 */
public class BobDue extends Device {

    // *** needed by the "reader" part
    private String buffer = "";
    private final String separator = "\n";


    // define the timeout we may wait to get an answer
    private static final int TIMEOUT = 250;
    // define End Of Message for this type of reader
    private static final char EOM = '\n';
    // the actual filter
    private int fieldIndex = 0;
    // the thread that polls the data to the stack

    @Override
    public void initConnection() {
        MainActivity.debug("BobDue: initConnection");
        // if the reading thread is running: stop it, because we don't need it
        if(connectedBluetoothThread!=null && connectedBluetoothThread.isAlive()) {
            connectedBluetoothThread.cleanStop();
            try {
                MainActivity.debug("BobDue: joining");
                connectedBluetoothThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        if(connectedBluetoothThread!=null) {
            MainActivity.debug("BobDue: connectedBluetoothThread!=null");
            // make sure we only have one poller task
            if (pollerThread == null) {
                MainActivity.debug("BobDue: pollerThread == null");
                // post a task to the UI thread
                setPollerActive(true);

                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        while (isPollerActive())
                        {
                            //MainActivity.debug("BobDue: inside poller thread ...");
                            if(fields.size()==0)
                            {
                                if(connectedBluetoothThread!=null)
                                    //MainActivity.debug("BobDue: sleeping ...");
                                    try{
                                        Thread.sleep(5000);
                                    }
                                    catch (Exception e) {}
                            }
                            // query a field
                            else {
                                //MainActivity.debug("BobDue: Doing next query ...");
                                queryNextFilter();
                            }
                        }
                    }
                };
                pollerThread = new Thread(r);
                pollerThread.start();
            }
        }
        else
        {
            MainActivity.debug("BobDue: connectedBluetoothThread == null");
            if(pollerThread!=null && pollerThread.isAlive())
            {
                setPollerActive(false);
                try {
                    MainActivity.debug("BobDue: joining pollerThread");
                    pollerThread.join();
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }
    }

    public void join() throws InterruptedException {
        pollerThread.join();
    }

    @Override
    public void registerFilter(int frameId) {
        String filter = Integer.toHexString(frameId);
        if(connectedBluetoothThread!=null)
            connectedBluetoothThread.write("f" + filter + "\n");
        else
            MainActivity.debug("BobDue.registerFilter " + filter + " failed because connectedBluetoothThread is NULL");
    }

    @Override
    public void unregisterFilter(int frameId) {
        String filter = Integer.toHexString(frameId);
        if(connectedBluetoothThread!=null)
            connectedBluetoothThread.write("r" + filter + "\n");
        else
            MainActivity.debug("BobDue.unregisterFilter " + filter + " failed because connectedBluetoothThread is NULL");
    }

    @Override
    protected ArrayList<Message> processData(int[] input) {
        ArrayList<Message> result = new ArrayList<>();

        // add to buffer as characters
        for (int i = 0; i < input.length; i++) {
            buffer += (char) input[i];
        }

        //MainActivity.debug("Buffer = "+buffer);

        // split by <new line>
        String[] messages = buffer.split(separator);
        // let assume the last message is fine
        int last = messages.length;
        // but if it is not, do not consider it
        if (!buffer.endsWith(separator)) last--;

        // process each message
        for (int i = 0; i < last; i++) {
            // decode into a frame
            //MainActivity.debug("Decoding: "+messages[i].trim());
            Message message = decodeFrame(messages[i].trim());
            // store if valid
            if (message != null)
                result.add(message);
        }
        // adapt the buffer
        if (!buffer.endsWith(separator))
            // retain the last uncompleted message
            buffer = messages[messages.length - 1];
        else
            // empty the entire buffer
            buffer = "";
        // we are done

        return result;
    }

    private Message decodeFrame(String text) {
        // split up the fields
        String[] pieces = text.split(",");
        //MainActivity.debug("Pieces = "+pieces);
        //MainActivity.debug("Size = "+pieces.length);
        if(pieces.length==2) {
            try {
                // get the id
                int id = Integer.parseInt(pieces[0], 16);
                // get the data
                int[] data = Utils.toIntArray(pieces[1].trim());
                // create and return new frame
                return new Message(id, data);
            }
            catch(Exception e)
            {
                //MainActivity.debug("BAD: "+text);
                return null;
            }
        }
        else if(pieces.length>=3) {
            try {
                // get the id
                int id = Integer.parseInt(pieces[0], 16);
                // get the data
                int[] data = Utils.toIntArray(pieces[1].trim());
                // get the reply-ID
                Message f = new Message(id,data);
                //MainActivity.debug("ID = "+id+" / Data = "+data);
                //MainActivity.debug("THIRD: "+pieces[2].trim());
                f.setResponseId(pieces[2].trim());
                return f;
                /*
                // get checksum
                int chk = Integer.parseInt(pieces[2].trim(), 16);
                int check = 0;
                for(int i=0; i<data.length; i++)
                    check ^= data[i];
                // validate the checksum
                if(chk==check)
                    // create and return new frame
                    return new Frame(id, data);
                */
            }
            catch(Exception e)
            {
                //MainActivity.debug("BAD: "+text);
                return null;
            }
        }
        //MainActivity.debug("BAD: "+text);
        return null;
    }


    // query the device for the next filter
    private void queryNextFilter()
    {
        if (fields.size() > 0) {
            try {
                // get field
                Field field;

                synchronized (fields) {
                    field = fields.get(fieldIndex);
                }

                MainActivity.debug("BobDue: queryNextFilter: "+fieldIndex+" --> "+field.getSID()+" \tSkipsCount = "+field.getSkipsCount());

                //MainActivity.debug("Querying for field: "+field.getSID());

                if(field!=null) {
                    process(Utils.toIntArray(requestField(field).getBytes()));

                    // goto next filter
                    synchronized (fields) {
                        if(fields.size()==0)
                            fieldIndex=0;
                        else
                            fieldIndex = (fieldIndex + 1) % fields.size();
                    }
                }
            } catch (Exception e) {
                fieldIndex =0;
            }
        } else {
            // ignore
        }
    }

    // send a command and wait for an answer
    private String sendAndWaitForAnswer(String command, int waitMillis)
    {
        // empty incoming buffer
        // just make sure there is no previous response
        try {
            while(connectedBluetoothThread.available()>0)
            {
                connectedBluetoothThread.read();
            }
        } catch (IOException e) {
            // ignore
        }
        // send the command
        if(command!=null)
            connectedBluetoothThread.write(command + "\r\n");
        //MainActivity.debug("Send > "+command);
        // wait if needed
        if(waitMillis>0)
            try {
                Thread.sleep(waitMillis);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        // init the buffer
        boolean stop = false;
        String readBuffer = "";
        // wait for answer
        long start = Calendar.getInstance().getTimeInMillis();
        while(!stop && Calendar.getInstance().getTimeInMillis()-start<TIMEOUT)
        {
            //MainActivity.debug("Delta = "+(Calendar.getInstance().getTimeInMillis()-start));
            try {
                // read a byte
                if(connectedBluetoothThread.available()>0) {
                    //MainActivity.debug("Reading ...");
                    int data = connectedBluetoothThread.read();
                    //MainActivity.debug("... done");
                    // if it is a real one
                    if (data != -1) {
                        // convert it to a character
                        char ch = (char) data;
                        // add it to the readBuffer
                        readBuffer += ch;
                        // stop if we reached the end or if no more data is available
                        if (ch == EOM || connectedBluetoothThread.available() <= 0) stop = true;
                    }
                }
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
        //MainActivity.debug("Recv < "+readBuffer);
        return readBuffer;
    }

    @Override
    public void clearFields() {
        super.clearFields();
        fieldIndex=0;
    }

    @Override
    public String requestFreeFrame(Field field) {
        // send the command and wait fir an answer, no delay
        return sendAndWaitForAnswer("g" + field.getHexId(), 0);
    }

    @Override
    public String requestIsoTpFrame(Field field) {
        // build the command string to send to the remote device
        String command = "i" + field.getHexId() + "," + field.getRequestId() + "," + field.getResponseId();
        // send and wait fir an answer, no delay
        return sendAndWaitForAnswer(command, 0);
    }
}
