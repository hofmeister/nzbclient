/*  
 * Copyright 2005 Patrick Wolf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cheesmo.nzb.client;

import com.cheesmo.nzb.codec.DecodingException;
import com.cheesmo.nzb.codec.SplitFileInputStream;
import com.cheesmo.nzb.codec.YEncDecoder;
import com.cheesmo.nzb.model.File;
import com.cheesmo.nzb.model.NZB;
import com.cheesmo.nzb.model.Segment;
import com.cheesmo.nzb.util.NzbUtils;
import java.io.FileNotFoundException;
import java.util.*;

public final class NzbClient {

    private final ClientConfig config;
    private ConnectionPool pool;

    public NzbClient(ClientConfig config) {
        this.config = config;

        pool = new ConnectionPool(config);

        java.io.File file = new java.io.File(config.getDownloadDir());
        if (!file.exists()) {
            file.mkdirs();
            System.out.println("Creating download directory " + file);
        }

        file = new java.io.File(config.getCacheDir());
        if (!file.exists()) {
            file.mkdirs();
            System.out.println("Creating cache directory " + file);
        }
    }

    public NzbClient() {
        this(new ClientConfig());
    }

    public List<String> start(String nzbPath) {
        List<String> out = new LinkedList<String>();
        
        NZB nzb = getNzb(nzbPath);
        List<File> files = nzb.getFiles();
        int fileCount = 1;
        for (Iterator<File> i = files.iterator(); i.hasNext();) {
            File file = i.next();
            System.out.println("File " + fileCount + "/" + files.size() + " " + file.getSubject());
            List<String> segmentNames = new ArrayList<String>();
            List<DownloadThread> downloadThreads = new ArrayList<DownloadThread>();


            for (Iterator<Segment> j = file.getSegments().
                    iterator(); j.hasNext();) {
                Segment seg = j.next();
                String downloadName = file.getSubject().
                        hashCode() + "_" + seg.getNumber() + ".yenc";

                //Thread thread = createDownloadSegThread(segCount, file.getGroups().get(0).getName(), "<" + seg.getString() + ">", downloadName);
                DownloadThread thread = createDownloadSegThread(pool, file.getGroups().
                        get(0).
                        getName(), "<" + seg.getString() + ">", downloadName);
                thread.start();
                try {
                    //Give some time for the thread to get started so that the joins complete in a good order.
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
                downloadThreads.add(thread);

                segmentNames.add(downloadName);
            }

            //Wait for all the threads to finish
            int segCount = 1;
            boolean failure = false;
            for (Iterator<DownloadThread> t = downloadThreads.iterator(); t.hasNext();) {
                try {
                    DownloadThread thread = t.next();
                    thread.join();
                    if (thread.getResult()) {
                        System.out.println("\t" + segCount + "/" + file.getSegments().
                                size() + " of " + file.getSubject());
                    } else {
                        System.err.println("\t" + segCount + "/" + file.getSegments().
                                size() + " of " + file.getSubject() + " failed.");
                        failure = true;
                    }
                    segCount++;
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }

            if (!failure) {
                String filename = launchDecode(segmentNames);
                if (filename != null)
                    out.add(filename);
            } else {
                System.err.println("Couldn't download all segments.  Skipping decode.");
                for (Iterator<String> j = segmentNames.iterator(); j.hasNext();) {
                    //clean up segments
                    new java.io.File(config.getCacheDir() + java.io.File.separator + j.next()).delete();
                }
            }

            fileCount++;
        }
        return out;
    }

    private String launchDecode(List<String> segmentNames) {
        
        try {

            //Make sure we decode in correct order
            Collections.sort(segmentNames, new Comparator<String>() {

                public int compare(String id1, String id2) {
                    Integer segmentNumber1 = Integer.parseInt(id1.substring(id1.indexOf("_") + 1, id1.indexOf(".")));
                    Integer segmentNumber2 = Integer.parseInt(id2.substring(id2.indexOf("_") + 1, id2.indexOf(".")));
                    return segmentNumber1.compareTo(segmentNumber2);
                }
            });

            SplitFileInputStream sfis = new SplitFileInputStream(config.getCacheDir(),
                    (String[]) segmentNames.toArray(new String[segmentNames.size()]), true);
            System.out.println("Decoding . . .");
            YEncDecoder decoder = new YEncDecoder(sfis, config.getDownloadDir());
            String fileDecoded = decoder.decode();

            if (fileDecoded != null) {
                System.out.println("Decoding " + fileDecoded + " complete.");
                return fileDecoded;
            } else {
                System.out.println("Couldn't decode.");
            }
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (DecodingException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return null;
    }

    private DownloadThread createDownloadSegThread(ConnectionPool pool, String group, String messageId,
            String downloadFilename) {
        return new DownloadThread(pool, group, messageId, downloadFilename);

    }

    private NZB getNzb(String path) {

        return NzbUtils.parseFile(path);
    }

    public static void printUsage() {
        System.out.println("Usage: nzbclient [options] <NZB FILE>");
        System.out.println("Downloads the specified file.\n");
        System.out.println("  -reset       resets all config settings and runs interactive configuration tool.");
        System.out.println("  -config      modify config settings.");
        System.out.println("  -keepparts   keep downloaded parts after decoding.");
    }
}
