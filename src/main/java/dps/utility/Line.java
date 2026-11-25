package dps.utility;

/**
 * A production line's network identity, as known to the rest of the
 * system: its id and where to reach it — used both for gRPC
 * peer-to-peer coordination (address/port of the line's own gRPC
 * server) and for the list the Administration Server returns on
 * registration.
 *
 * @param id the line's unique identifier
 * @param address the host where the line's gRPC server is listening
 * @param port the port where the line's gRPC server is listening
 */
public record Line(int id, String address, int port) {
    @Override
    public String toString() {
        return "{" + "id=" + id + ", address=" + address + ", port=" + port + "}";
    }
}