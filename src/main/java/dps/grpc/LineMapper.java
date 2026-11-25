package dps.grpc;

import dps.utility.Line;
import dps.utility.OperationalState;

/**
 * Converts between domain types ({@link dps.utility.Line}, {@link OperationalState})
 * and the corresponding types generated from the gRPC/Protobuf definitions.
 * Kept as a single shared boundary so the rest of the of codebase never
 * depends directly on generated proto classes.
 */
public final class LineMapper {

    private LineMapper() {}

    /**
     * @param line domain line ({@link dps.utility.Line})
     * @return the equivalent generated proto message ({@link proto.generated.Line})
     */
    public static proto.generated.Line toProto(Line line) {
        return proto.generated.Line.newBuilder()
                .setId(line.id())
                .setAddress(line.address())
                .setPort(line.port())
                .build();
    }

    /**
     * @param proto generated proto message ({@link proto.generated.Line})
     * @return the equivalent domain line ({@link dps.utility.Line})
     */
    public static Line fromProto(proto.generated.Line proto) {
        return new Line(proto.getId(), proto.getAddress(), proto.getPort());
    }
}
