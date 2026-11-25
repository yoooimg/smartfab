package dps.rest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method or class as excluded from JaCoCo coverage metrics.
 * JaCoCo automatically recognizes any annotation whose simple name is
 * {@code Generated} — regardless of its package — as long as it
 * survives into the compiled bytecode (hence {@link RetentionPolicy#CLASS},
 * the minimum needed; {@code javax.annotation.processing.Generated}
 * does NOT work for this because it is source-only and never reaches
 * the .class file at all).
 *
 * <p>Used only on {@code AdminClientCLI#dispatchOption(int, Scanner)}, whose switch
 * statement has one branch (no case matched) that is structurally
 * unreachable — {@code option} is always validated to be in [1,4]
 * before this method is ever called — so there is nothing meaningful
 * a test could exercise there without deliberately bypassing that
 * validation.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.TYPE})
@interface Generated {}
