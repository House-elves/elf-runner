/** Run by the sandbox probe: Java's temp dir must be one the agent can write. */
public class TempFile {
    public static void main(String[] args) throws Exception {
        var f = java.nio.file.Files.createTempFile("probe", ".tmp");
        java.nio.file.Files.delete(f);
        System.out.println("temp file in " + f.getParent());
    }
}
