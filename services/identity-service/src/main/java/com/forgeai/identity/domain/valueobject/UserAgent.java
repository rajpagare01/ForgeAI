package com.forgeai.identity.domain.valueobject;
import java.util.Objects;
public final class UserAgent {
    private final String agent;
    private UserAgent(String agent) { this.agent = agent == null ? "Unknown" : agent; }
    public static UserAgent of(String agent) { return new UserAgent(agent); }
    public String value() { return agent; }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserAgent userAgent = (UserAgent) o;
        return agent.equals(userAgent.agent);
    }
    @Override public int hashCode() { return Objects.hash(agent); }
    @Override public String toString() { return agent; }
}
