package org.dice_research.squirrel.fetcher;

import org.dice_research.squirrel.data.uri.CrawleableUri;
import org.dice_research.squirrel.queue.InMemoryQueue;
import org.dice_research.squirrel.queue.IpAddressBasedQueue;
import org.dice_research.squirrel.sink.Sink;
import org.dice_research.squirrel.sink.impl.mem.InMemorySink;

import java.util.ArrayList;
import java.util.List;

/*
 * TODO Has to be reworked or deleted
 */
@Deprecated
public class FetcherTest {

    public void run(CrawleableUri crawleableUri) {
        Sink sink = new InMemorySink();
        IpAddressBasedQueue queue = new InMemoryQueue();
        System.out.println(crawleableUri);

        List<CrawleableUri> urisToAdd = new ArrayList<>();
        urisToAdd.add(crawleableUri);
        queue.addUris(urisToAdd);

//        Frontier frontier = new FrontierImpl(new InMemoryKnownUriFilter(-1), queue);
//        Worker worker = new WorkerImpl(ZeroMQBasedFrontierClient.create(FRONTIER_ADDRESS, 0), sink,
//                new RobotsManagerImpl(new SimpleHttpFetcher(new UserAgent("Test", "", ""))),
//                new GzipJavaUriSerializer(), 2000);
//        (new Thread(worker)).start();
//        frontierWrapper.run();
    }
}
