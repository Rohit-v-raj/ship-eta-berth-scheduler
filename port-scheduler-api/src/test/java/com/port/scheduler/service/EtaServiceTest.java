package com.port.scheduler.service;

import com.port.scheduler.exceptions.EtaServiceUnavailableException;
import com.port.scheduler.model.EtaQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The model service client gives up in time when the service is down or does not answer. */
class EtaServiceTest {

    private static final EtaQuery QUERY = new EtaQuery("V001", 61.5, 4.9, 12.0, 45.0, 61.612, 5.07, null);

    private final List<Socket> held = new CopyOnWriteArrayList<>();
    private ServerSocket silent;

    @AfterEach
    void stopTheSilentService() throws IOException {
        for (Socket socket : held) {
            socket.close();
        }
        if (silent != null) {
            silent.close();
        }
    }

    @Test
    void aServiceThatAcceptsButNeverAnswersIsReportedAfterTheReadTimeout() throws IOException {
        silent = new ServerSocket(0);
        Thread acceptor = new Thread(() -> {
            while (!silent.isClosed()) {
                try {
                    held.add(silent.accept()); // keep the connection open and say nothing
                } catch (IOException closed) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        EtaService service = new EtaService("http://localhost:" + silent.getLocalPort(), 1, 1);

        Instant start = Instant.now();
        assertThatThrownBy(() -> service.predict(QUERY))
                .isInstanceOf(EtaServiceUnavailableException.class)
                .hasMessage("The ETA model service is not answering; try again shortly");
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aServiceThatIsNotRunningIsReportedAtOnce() throws IOException {
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }
        EtaService service = new EtaService("http://localhost:" + freePort, 1, 1);

        assertThatThrownBy(() -> service.predict(QUERY)).isInstanceOf(EtaServiceUnavailableException.class);
    }
}
