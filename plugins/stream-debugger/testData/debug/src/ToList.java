import java.util.stream.Stream;

public class ToList {
  public static void main(String[] args) {
    // Breakpoint!
    Stream.of(1, 2, 3, 4, 5, 6).filter(x -> x < 3).toList();
  }
}
