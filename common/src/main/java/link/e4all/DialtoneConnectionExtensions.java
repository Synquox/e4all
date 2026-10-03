package link.e4all;

public interface DialtoneConnectionExtensions {
    byte[] e4mc$exportKeyingMaterial(byte[] label, byte[] context, int length);
    String e4mc$connInfo();
}

