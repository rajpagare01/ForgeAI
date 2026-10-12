package com.forgeai.testing.infrastructure.parser;

import com.forgeai.testing.domain.model.TestResultStatus;
import lombok.Builder;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class JunitXmlParser {

    private static final Pattern REQ_PATTERN = Pattern.compile("REQ-\\d+", Pattern.CASE_INSENSITIVE);

    @Value
    @Builder
    public static class ParsedSuite {
        String name;
        List<ParsedCase> cases;
    }

    @Value
    @Builder
    public static class ParsedCase {
        String name;
        String classname;
        String requirementKey;
        TestResultStatus status;
        long durationMs;
        String errorMessage;
        String stackTrace;
    }

    public List<ParsedSuite> parse(byte[] xmlBytes) {
        return parse(new ByteArrayInputStream(xmlBytes));
    }

    public List<ParsedSuite> parse(InputStream inputStream) {
        List<ParsedSuite> suites = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Disallow XXE injection
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(inputStream);
            doc.getDocumentElement().normalize();

            NodeList suiteNodes = doc.getElementsByTagName("testsuite");
            if (suiteNodes.getLength() == 0 && "testsuite".equalsIgnoreCase(doc.getDocumentElement().getNodeName())) {
                suites.add(parseSuiteElement(doc.getDocumentElement()));
            } else {
                for (int i = 0; i < suiteNodes.getLength(); i++) {
                    Element suiteElem = (Element) suiteNodes.item(i);
                    suites.add(parseSuiteElement(suiteElem));
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse JUnit XML report", e);
            throw new IllegalArgumentException("Invalid JUnit XML report: " + e.getMessage(), e);
        }
        return suites;
    }

    private ParsedSuite parseSuiteElement(Element suiteElem) {
        String suiteName = suiteElem.getAttribute("name");
        if (suiteName == null || suiteName.isBlank()) {
            suiteName = "default-suite";
        }

        List<ParsedCase> cases = new ArrayList<>();
        NodeList caseNodes = suiteElem.getElementsByTagName("testcase");
        for (int i = 0; i < caseNodes.getLength(); i++) {
            Element caseElem = (Element) caseNodes.item(i);
            cases.add(parseCaseElement(caseElem));
        }

        return ParsedSuite.builder()
                .name(suiteName)
                .cases(cases)
                .build();
    }

    private ParsedCase parseCaseElement(Element caseElem) {
        String name = caseElem.getAttribute("name");
        String classname = caseElem.getAttribute("classname");
        String timeStr = caseElem.getAttribute("time");

        long durationMs = 0L;
        if (timeStr != null && !timeStr.isBlank()) {
            try {
                durationMs = (long) (Double.parseDouble(timeStr) * 1000.0);
            } catch (NumberFormatException ignored) {}
        }

        // Determine requirement key
        String reqKey = caseElem.getAttribute("requirement");
        if (reqKey == null || reqKey.isBlank()) {
            reqKey = extractRequirementKey(name + " " + classname);
        }

        // Determine status and failure details
        TestResultStatus status = TestResultStatus.PASSED;
        String errorMessage = null;
        String stackTrace = null;

        NodeList failureNodes = caseElem.getElementsByTagName("failure");
        NodeList errorNodes = caseElem.getElementsByTagName("error");
        NodeList skippedNodes = caseElem.getElementsByTagName("skipped");

        if (failureNodes.getLength() > 0) {
            status = TestResultStatus.FAILED;
            Element f = (Element) failureNodes.item(0);
            errorMessage = f.getAttribute("message");
            stackTrace = f.getTextContent();
        } else if (errorNodes.getLength() > 0) {
            status = TestResultStatus.FAILED;
            Element err = (Element) errorNodes.item(0);
            errorMessage = err.getAttribute("message");
            stackTrace = err.getTextContent();
        } else if (skippedNodes.getLength() > 0) {
            status = TestResultStatus.SKIPPED;
            Element sk = (Element) skippedNodes.item(0);
            errorMessage = sk.getAttribute("message");
        }

        return ParsedCase.builder()
                .name(name != null ? name : "unnamed-test")
                .classname(classname)
                .requirementKey(reqKey)
                .status(status)
                .durationMs(durationMs)
                .errorMessage(errorMessage)
                .stackTrace(stackTrace)
                .build();
    }

    private String extractRequirementKey(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = REQ_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group().toUpperCase();
        }
        return null;
    }
}
