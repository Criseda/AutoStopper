package me.criseda.autostopper.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

public class DockerEndpointTest {

    @Test
    public void parsesUnixSockets() {
        DockerEndpoint endpoint = DockerEndpoint.parse("unix:///var/run/docker.sock").orElseThrow();

        assertEquals(DockerEndpoint.Kind.UNIX, endpoint.kind());
        assertEquals("/var/run/docker.sock", endpoint.socketPath());
        assertEquals("unix:///var/run/docker.sock", endpoint.toString());
    }

    @Test
    public void parsesTcpHostsWithDefaultPort() {
        assertEquals(DockerEndpoint.tcp("autostopper-docker", 2375),
                DockerEndpoint.parse("tcp://autostopper-docker:2375").orElseThrow());
        assertEquals(DockerEndpoint.tcp("socket_proxy", 2375),
                DockerEndpoint.parse("tcp://socket_proxy").orElseThrow(),
                "Compose service names may contain underscores");
        assertEquals(DockerEndpoint.tcp("10.0.0.5", 2375), DockerEndpoint.parse("tcp://10.0.0.5:2375/").orElseThrow());
        DockerEndpoint ipv6 = DockerEndpoint.parse("tcp://[::1]:2376").orElseThrow();
        assertEquals("::1", ipv6.host());
        assertEquals("tcp://[::1]:2376", ipv6.toString());
    }

    @Test
    public void rejectsUnsupportedOrMalformedEndpoints() {
        for (String invalid : List.of("", " unix:///var/run/docker.sock", "unix://", "unix:///",
                "unix://docker.sock", "ssh://user@host", "npipe:////./pipe/docker_engine",
                "http://host:2375", "tcp://", "tcp://:2375", "tcp://host:0", "tcp://host:65536",
                "tcp://host:port", "tcp://host:2375/v1.40", "tcp://user@host:2375", "tcp://[::1",
                "tcp://[zz]:2375", "auto")) {
            assertEquals(Optional.empty(), DockerEndpoint.parse(invalid), invalid);
        }
        assertEquals(Optional.empty(), DockerEndpoint.parse(null));
    }
}
