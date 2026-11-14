package com.meetodo.rag.index;

public record RagIndexStatus(int eligible, int indexed, int failed, int pending, String namespace) {
    public boolean ready() { return failed == 0 && pending == 0; }
}
