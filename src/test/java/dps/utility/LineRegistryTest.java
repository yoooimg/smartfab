package dps.utility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link LineRegistry}: registration must reject
 * duplicate ids (address/port ignored for the identity check),
 * {@code otherLines} must exclude the caller by id, and {@code remove}
 * must report whether anything was actually removed.
 */
class LineRegistryTest {

    LineRegistry registry;

    @BeforeEach
    public void setup() {
        registry = new LineRegistry();
    }

    @AfterEach
    public void teardown() {
        registry = null;
    }

    @Test
    void whenNewId_thenRegisterSucceed() {
        boolean registered = registry.register(new Line(1, "localhost", 9001));
        assertThat(registered).isTrue();
    }

    @Test
    void whenDuplicateId_thenRegistryRejects() {
        registry.register(new Line(1, "localhost", 9001));
        boolean registeredAgain = registry.register(new Line(1, "localhost", 9002));
        assertThat(registeredAgain).isFalse();
    }

    @Test
    void whenOtherLines_thenGivenIdIsExcluded() {
        Line l1 = new Line(1, "localhost", 9001);
        Line l2 = new Line(2, "localhost", 9002);
        registry.register(l1);
        registry.register(l2);
        assertThat(registry.otherLines(l1)).containsExactly(l2);
    }

    @Test
    void whenRemove_thenRegistryNoMoreContainsLine() {
        Line l1 = new Line(1, "localhost", 9001);
        registry.register(l1);
        registry.remove(1);
        assertThat(registry.all().contains(l1)).isFalse();
    }

    @Test
    void whenIdNotPresent_thenRemoveReturnsFalse() {
        boolean removed = registry.remove(10);
        assertThat(removed).isFalse();
    }

    @Test
    void whenAll_thenImmutableSnapshot() {
        registry.register(new Line(1, "localhost", 9001));
        var snapshot = registry.all();
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(new Line(2, "localhost", 9002)));
    }

    @Test
    void remove_withOtherLinesPresent_butNotTheTargetId_returnsFalse() {
        registry.register(new Line(1, "localhost", 9001));
        var removed = registry.remove(2);
        assertThat(removed).isFalse();
        assertThat(registry.all()).hasSize(1);
    }
}