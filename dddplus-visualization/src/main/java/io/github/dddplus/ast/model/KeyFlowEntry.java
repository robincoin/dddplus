/*
 * Copyright DDDplus Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.dddplus.ast.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Data
public class KeyFlowEntry {
    private final String className;
    private final String realMethodName;
    private final String javadoc;
    private String actor;
    private String methodName;
    private Set<String> events = new TreeSet<>(); // 该流程产生哪些领域事件
    private List<String> args;
    private Set<String> realArguments;
    private String remark;
    private boolean async = false;
    private boolean polymorphism = false;
    private boolean useRawArgs = false;

    public KeyFlowEntry(String className, String realMethodName, String javadoc) {
        this.className = className;
        this.realMethodName = realMethodName;
        this.methodName = realMethodName;
        this.javadoc = javadoc;

        this.actor = "";
    }

    public String actor() {
        if (actor.isEmpty()) {
            return className;
        }

        return actor;
    }

    public boolean produceEvent() {
        return events != null && !events.isEmpty();
    }

    public String displayEvents() {
        return String.join(",", events);
    }

    /**
     * 没有被{@link AggregateEntry}收录到{@link KeyModelEntry}.
     */
    public boolean isOrphan() {
        return !className.equals(actor);
    }

    public String displayActualClass() {
        if (!actor.equals(className)) {
            return className;
        }

        return "";
    }

    private String displayArgs() {
        if (useRawArgs) {
            return String.join(",", realArguments);
        }

        if (args == null || args.isEmpty()) {
            return "";
        }

        return String.join(",", args);
    }

    public String displayEffectiveArgs() {
        String args = displayArgs();
        List<String> l = new ArrayList<>();
        if (realArguments != null && !realArguments.isEmpty()) {
            l.addAll(realArguments);
        }
        if (!args.isEmpty()) {
            l.add(args);
        }
        return String.join(",", l);
    }

    public String displayNameWithRemark() {
        if (remark == null || remark.isEmpty()) {
            return methodName;
        }

        return methodName + "/" + remark;
    }
}
