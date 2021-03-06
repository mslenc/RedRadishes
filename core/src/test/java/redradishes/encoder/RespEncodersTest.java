package redradishes.encoder;

import com.pholser.junit.quickcheck.ForAll;
import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.generator.ValuesOf;
import com.pholser.junit.quickcheck.generator.java.lang.Encoded;
import com.pholser.junit.quickcheck.generator.java.lang.Encoded.InCharset;
import org.junit.contrib.theories.DataPoints;
import org.junit.contrib.theories.Theories;
import org.junit.contrib.theories.Theory;
import org.junit.runner.RunWith;

import java.nio.charset.Charset;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_16BE;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static redradishes.encoder.TestUtil.respBulkString;
import static redradishes.encoder.TestUtil.serialize;

@RunWith(Theories.class)
public class RespEncodersTest {
  @DataPoints
  public static final Charset[] CHARSETS = {UTF_8, UTF_16BE, UTF_16LE};
  @DataPoints
  public static final int[] INTS =
      {0, 1, 9, 10, 99, 100, 100, -1, -9, -10, -99, -100, Integer.MAX_VALUE, Integer.MIN_VALUE};
  @DataPoints
  public static final long[] LONGS =
      {0, 1, 9, 10, 99, 100, 100, -1, -9, -10, -99, -100, Long.MAX_VALUE, Long.MIN_VALUE};

  @Theory
  public void testArray(@ForAll int i, @ForAll @ValuesOf boolean compact) {
    ConstExpr expr = RespEncoders.array().encode(i);
    ConstExpr c = compact ? expr.compact() : expr;
    assertEquals(0, c.size());
    assertThat(serialize(c), equalTo(String.format("*%d\r\n", i).getBytes(US_ASCII)));
  }

  @Theory
  public void testOneByteCharsetStrBulkString(@ForAll @From(Encoded.class) @InCharset("ISO-8859-1") String s,
      @ForAll @ValuesOf boolean compact) {
    testStrBulkString(s, ISO_8859_1, compact);
  }

  @Theory
  public void testStrBulkString(@ForAll String s, Charset charset, @ForAll @ValuesOf boolean compact) {
    ConstExpr expr = RespEncoders.strBulkString(charset).encode(s);
    ConstExpr c = compact ? expr.compact() : expr;
    assertEquals(1, c.size());
    byte[] bytes = s.getBytes(charset);
    assertThat(serialize(c), equalTo(respBulkString(bytes)));
  }

  @Theory
  public void testBytesBulkString(@ForAll byte[] bytes, @ForAll @ValuesOf boolean compact) {
    ConstExpr expr = RespEncoders.bytesBulkString().encode(bytes);
    ConstExpr c = compact ? expr.compact() : expr;
    assertEquals(1, c.size());
    assertThat(serialize(c), equalTo(respBulkString(bytes)));
  }

  @Theory
  public void testIntBulkString(int i, @ForAll @ValuesOf boolean compact) {
    ConstExpr expr = RespEncoders.intBulkString().encode(i);
    ConstExpr c = compact ? expr.compact() : expr;
    assertEquals(1, c.size());
    String s = Integer.toString(i);
    assertThat(serialize(c), equalTo(String.format("$%d\r\n%s\r\n", s.length(), s).getBytes(US_ASCII)));
  }

  @Theory
  public void testLongBulkString(long i, @ForAll @ValuesOf boolean compact) {
    ConstExpr expr = RespEncoders.longBulkString().encode(i);
    ConstExpr c = compact ? expr.compact() : expr;
    assertEquals(1, c.size());
    String s = Long.toString(i);
    assertThat(serialize(c), equalTo(String.format("$%d\r\n%s\r\n", s.length(), s).getBytes(US_ASCII)));
  }

  @Theory
  public void testToBytes(@ForAll long i) throws Exception {
    assertThat(RespEncoders.toBytes(i), equalTo(Long.toString(i).getBytes(US_ASCII)));
  }
}
