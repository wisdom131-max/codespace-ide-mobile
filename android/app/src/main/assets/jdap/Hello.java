public class Hello {
    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 10_000; i++) {
            int result = compute(i);
            System.out.println("iter " + i + " -> " + result);
        }
        System.out.println("DONE");
    }
    static int compute(int n) {
        return n * 2 + 1;   // BREAK HERE (line 10)
    }
}
