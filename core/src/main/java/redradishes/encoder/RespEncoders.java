package redradishes.encoder;

import com.google.common.base.Utf8;
import redradishes.UncheckedCharacterCodingException;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static redradishes.encoder.ConstExpr.NEW_ARG;
import static redradishes.encoder.ConstExpr.byteConst;
import static redradishes.encoder.ConstExpr.bytesConst;
import static redradishes.encoder.Encoder.bytesEnc;
import static redradishes.encoder.Encoder.stringEnc;
import static redradishes.encoder.IntEncoder.digitEncoder;

public class RespEncoders {
  private static final byte[][] NUM_BYTES =
      IntStream.rangeClosed(10, 99).mapToObj(i -> Integer.toString(i).getBytes(US_ASCII)).toArray(byte[][]::new);
  private static final byte[] MIN_LONG_BYTES = "-9223372036854775808".getBytes(US_ASCII);
  private static final long[] SIZE_TABLE = LongStream.iterate(10, x -> x * 10).limit(18).map(x -> x - 1).toArray();
  private static final ConstExpr CR_LF = bytesConst(new byte[]{'\r', '\n'});
  private static final ConstExpr MIN_LONG_BULK_STRING =
      charConst('2').append(charConst('0')).append(CR_LF).append(bytesConst(MIN_LONG_BYTES)).compact();
  private static final ConstExpr EMPTY_BULK_STRING = charConst('0').append(CR_LF).compact();
  private static final IntEncoder ONE_DIGIT_AS_BULK_STRING = charConst('1').append(CR_LF).append(digitEncoder());
  private static final IntEncoder TWO_DIGITS_AS_BULK_STRING =
      charConst('2').append(CR_LF).append(bytesEnc().mapToIntEncoder(num -> NUM_BYTES[num - 10]));
  private static final IntEncoder ARRAY = charConst('*').append(intEnc()).append(CR_LF).compact();
  private static final ThreadLocal<Map<Charset, CharsetEncoder>> charsetDecodersMap =
      new ThreadLocal<Map<Charset, CharsetEncoder>>() {
        @Override
        protected Map<Charset, CharsetEncoder> initialValue() {
          return new WeakHashMap<>();
        }
      };

  public static IntEncoder array() {
    return ARRAY;
  }

  private static ConstExpr charConst(char c) {
    return byteConst((byte) c);
  }

  public static Encoder<CharSequence> strBulkString(Charset charset) {
    if (UTF_8.equals(charset)) {
      return NEW_ARG.append(Encoder.choiceConst(s -> s.length() == 0, EMPTY_BULK_STRING,
          intEnc().map(Utf8::encodedLength).append(CR_LF).zip(stringEnc(charset)))).append(CR_LF);
    } else {
      CharsetEncoder charsetEncoder = getCharsetEncoder(charset);
      if (charsetEncoder.maxBytesPerChar() == 1.0) {
        return NEW_ARG.append(Encoder.choiceConst(s -> s.length() == 0, EMPTY_BULK_STRING,
            intEnc().map(CharSequence::length).append(CR_LF).zip(stringEnc(charset)))).append(CR_LF);
      } else {
        return NEW_ARG.append((Encoder<CharSequence>) s -> {
          if (s.length() == 0) {
            return EMPTY_BULK_STRING;
          } else {
            try {
              ByteBuffer byteBuffer = encodeCharSeq(s, charsetEncoder);
              int encodedLength = byteBuffer.remaining();
              return intEnc().encode(encodedLength).append(CR_LF)
                  .append(bytesConst(byteBuffer.array(), 0, encodedLength));
            } catch (CharacterCodingException e) {
              throw new UncheckedCharacterCodingException(e);
            }
          }
        }).append(CR_LF);
      }
    }
  }

  static CharsetEncoder getCharsetEncoder(Charset charset) {
    return charsetDecodersMap.get().computeIfAbsent(charset, Charset::newEncoder);
  }

  private static ByteBuffer encodeCharSeq(CharSequence s, CharsetEncoder charsetEncoder)
      throws CharacterCodingException {
    int maxLength = (int) (s.length() * (double) charsetEncoder.maxBytesPerChar());
    ByteBuffer byteBuffer = ByteBuffer.allocate(maxLength);
    CharBuffer charBuffer = CharBuffer.wrap(s);
    CoderResult coderResult = charsetEncoder.reset().encode(charBuffer, byteBuffer, true);
    if (coderResult.isUnderflow()) {
      coderResult = charsetEncoder.flush(byteBuffer);
    }
    if (!coderResult.isUnderflow()) {
      coderResult.throwException();
    }
    byteBuffer.flip();
    return byteBuffer;
  }

  public static Encoder<byte[]> bytesBulkString() {
    return NEW_ARG.append(arrayLenEnc()).append(CR_LF).zip(bytesEnc()).append(CR_LF);
  }

  private static Encoder<byte[]> arrayLenEnc() {
    return intEnc().map(arr -> arr.length);
  }

  public static Encoder<Integer> intBulkString() {
    return NEW_ARG.append(IntEncoder.choice(num -> num >= 0 && num <= 9, ONE_DIGIT_AS_BULK_STRING, IntEncoder
        .choice(num -> num >= 10 && num <= 99, TWO_DIGITS_AS_BULK_STRING,
            longAsBulkString().mapToIntEncoder(Long::valueOf))).map(Integer::intValue)).append(CR_LF);
  }

  public static Encoder<Long> longBulkString() {
    return NEW_ARG.append(Encoder.choice(num -> num >= 0 && num <= 9, ONE_DIGIT_AS_BULK_STRING.map(Long::intValue),
        Encoder.choice(num -> num >= 10 && num <= 99, TWO_DIGITS_AS_BULK_STRING.map(Long::intValue),
            Encoder.choiceConst(num -> num == Long.MIN_VALUE, MIN_LONG_BULK_STRING, longAsBulkString()))))
        .append(CR_LF);
  }

  private static Encoder<Long> longAsBulkString() {
    return num -> {
      byte[] bytes = toBytes(num);
      int len = bytes.length;
      return (len <= 9 ? byteConst((byte) ('0' + len)) : bytesConst(NUM_BYTES[len - 10])).append(CR_LF)
          .append(bytesConst(bytes));
    };
  }

  private static IntEncoder intEnc() {
    return num -> {
      if (num >= 0 && num <= 9) {
        return byteConst((byte) ('0' + num));
      } else if (num >= 10 && num <= 99) {
        return bytesConst(NUM_BYTES[num - 10]);
      }
      return bytesConst(toBytes(num));
    };
  }

  static byte[] toBytes(long num) {
    if (num == Long.MIN_VALUE) return MIN_LONG_BYTES;
    boolean neg = num < 0;
    if (neg) {
      num = -num;
    }
    int size = neg ? stringSize(num) + 1 : stringSize(num);
    byte[] buf = new byte[size];
    if (neg) {
      buf[0] = '-';
    }
    int i = size - 1;
    while (num != 0) {
      buf[i--] = (byte) ('0' + num % 10);
      num /= 10;
    }
    return buf;
  }

  private static int stringSize(long x) {
    for (int i = 0; i < SIZE_TABLE.length; i++) {
      if (x <= SIZE_TABLE[i]) {
        return i + 1;
      }
    }
    return 19;
  }
}
