package kr.asmbl.figuracontroller.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** 하네스 파이썬 참조 구현이 만든 시험 벡터와 바이트 단위로 같은가 */
class VectorTest {
    /** 벡터 수가 줄면(파일이 잘리면) 잡으려는 하한 — 벡터를 늘리면 같이 올린다 */
    private static final int FLOOR = 60;

    @Test
    void vectors() throws IOException {
        String json;
        try (InputStream in = VectorTest.class.getResourceAsStream("/figuracontroller/vectors.json")) {
            assertNotNull(in, "vectors.json 이 없다");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        VectorCheck.Result r = VectorCheck.run(json);
        assertEquals(java.util.List.of(), r.failures());
        assertTrue(r.passed() >= FLOOR, "벡터가 줄었다: " + r.passed() + " < " + FLOOR);
    }
}
