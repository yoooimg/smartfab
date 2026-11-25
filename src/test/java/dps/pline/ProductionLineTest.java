package dps.pline;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit test for {@link ProductionLine#computeCriticality(double)} - the one piece of pure
 * logic inside {@link ProductionLine}. Everything else in that class is deeply intertwined
 * with real threads, gRPC, MQTT and a live sensor, and is instead covered by the manual
 * multi-process end-to-end testing already carried out.
 */
class ProductionLineTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                    " 80.0  | 0.0  ",
                    " 100.0 | 0.25 ",
                    " 160.0 | 1.0  ",
                    " 40.0  | -0.5 "
            }
    )
    void average_yieldsExpectedCriticality(double average, double expectedCriticality) {
        assertThat(ProductionLine.computeCriticality(average)).isCloseTo(expectedCriticality, within(1e-9));
    }
}

