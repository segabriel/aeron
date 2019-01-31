package io.aeron.samples.mdc;

import io.aeron.Aeron;
import io.aeron.Image;
import io.aeron.samples.mdc.AeronResources.MsgPublication;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * VM options:
 * -XX:BiasedLockingStartupDelay=0
 * -XX:+UnlockDiagnosticVMOptions
 * -XX:GuaranteedSafepointInterval=300000
 * -Djava.net.preferIPv4Stack=true
 * -Daeron.mtu.length=1k
 * -Daeron.socket.so_sndbuf=4k
 * -Daeron.socket.so_rcvbuf=4k
 * -Daeron.rcv.initial.window.length=4k
 * -Dagrona.disable.bounds.checks=true
 * -Daeron.term.buffer.sparse.file=false
 */
public class AeronServerPong {

  private static final int MAX_POLL_FRAGMENT_LIMIT = 8;

  /**
   * Main runner.
   *
   * @param args program arguments.
   */
  public static void main(String[] args) {
    Aeron aeron = AeronResources.start();
    new Server(aeron).start();
  }

  private static class Server extends AeronServer {


    Server(Aeron aeron) {
      super(aeron);
    }

    @Override
    int process(Image image, MsgPublication publication) {
      int result = 0;
      result +=
          image.poll(
              (buffer, offset, length, header) -> {
                UnsafeBuffer incoming = new UnsafeBuffer(buffer, offset, length);
                int r;
                do {
                  r = publication.proceed(incoming);
                } while (r < 1);
              },
              MAX_POLL_FRAGMENT_LIMIT);
      return result;
    }
  }
}
