package org.jgroups.util;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * Dumps the data (tests.data) generated by {@link JUnitXMLReporter} to stdout
 * @author Bela Ban
 * @since 3.1
 */
public class DumpData {
    public static void main(String[] args) throws FileNotFoundException {
        if(args.length == 0) {
            System.out.println("DumpData <tests.data>");
            return;
        }

        File file=new File(args[0]);
        if(!file.exists()) {
            System.err.println(file + " not found");
            return;
        }
        List<JUnitXMLReporter.TestCase> test_cases=new ArrayList<>();
        DataInputStream input=new DataInputStream(new FileInputStream(file));
        try {
            for(;;) {
                JUnitXMLReporter.TestCase test_case=new JUnitXMLReporter.TestCase();
                try {
                    test_case.readFrom(input);
                    test_cases.add(test_case);
                }
                catch(Exception e) {
                    break;
                }
            }
        }
        finally {
            Util.close(input);
        }

        if(test_cases.isEmpty()) {
            System.err.println("No test cases found in " + file);
            return;
        }

        int num_failures=JUnitXMLReporter.getFailures(test_cases);
        int num_skips=JUnitXMLReporter.getSkips(test_cases);
        int num_errors=JUnitXMLReporter.getErrors(test_cases);
        long total_time=JUnitXMLReporter.getTotalTime(test_cases);

        int cnt=1;
        for(JUnitXMLReporter.TestCase test_case: test_cases) {
            System.out.println(cnt++ + ": " + test_case);
        }

        System.out.println(Util.bold(test_cases.size() + " tests " + num_failures + " failures " + num_errors +
                                       " errors " + num_skips + " skips time=" + (total_time / 1000.0) + "\n"));
    }
}
