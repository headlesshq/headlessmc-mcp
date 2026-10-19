package io.github.headlesshq.mcp.tools;

import org.jline.reader.Parser;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArgsTest {
    @Test
    void testWordsAreNotQuoted() {
        assertEquals("1.21.5", Args.word("1.21.5"));
        assertEquals("\"\"", Args.word(""));
    }

    @Test
    void testQuotedArgsSurviveSplitting() {
        // HeadlessMc and HMC-Specifics split command lines with JLine's DefaultParser
        for (String arg : List.of("hello world", "say \"hi\"", "C:\\Users\\Steve Minecraft", "-Dhmc.mcp.portFile=/tmp/a b/c.port")) {
            String line = "text 3 " + Args.word(arg);
            List<String> words = new DefaultParser().parse(line, line.length(), Parser.ParseContext.ACCEPT_LINE).words();
            assertEquals(List.of("text", "3", arg), words);
        }
    }

}
