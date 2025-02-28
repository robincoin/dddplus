/*
 * Copyright DDDplus Authors.
 *
 * Licensed under the Apache License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.dddplus.ast.view;

import com.google.common.collect.Sets;
import io.github.dddplus.ast.model.ReverseEngineeringModel;
import io.github.dddplus.ast.model.*;
import io.github.dddplus.ast.report.ClassMethodReport;
import io.github.dddplus.ast.report.CoverageReport;
import io.github.dddplus.dsl.KeyElement;
import io.github.dddplus.dsl.KeyRelation;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.*;

/**
 * DSL -> Reverse Engineering Model -> PlantUML DSL.
 *
 * @see <a href="https://www.augmentedmind.de/2021/01/17/plantuml-layout-tutorial-styles/">PlantUml Layout Guide</a>
 */
public class PlantUmlRenderer implements IModelRenderer<PlantUmlRenderer> {
    /**
     * Direction to render the plantuml.
     */
    public enum Direction {
        TopToBottom,
        LeftToRight,
    }

    private static final String STARTUML = "@startuml";
    private static final String ENDUML = "@enduml";
    private static final String BRACE_OPEN = "{";
    private static final String BRACE_CLOSE = "}";
    private static final String QUOTE = "\"";
    private static final String HASHTAG = "#";
    private static final String PACKAGE_TMPL = "package {0} <<{1}>>";
    private static final String COLOR_TMPL_OPEN = "<color:{0}>";
    private static final String COLOR_TMPL_CLOSE = "</color>";
    private static final String BRACKET_OPEN = "(";
    private static final String BRACKET_CLOSE = ")";
    private static final String DIRECTION_TOP_BOTTOM = "left to right direction";
    private static final String DIRECTION_LEFT_RIGHT = "top to bottom direction";

    // https://plantuml.com/zh/color
    private static final String COLOR_BEHAVIOR_PRODUCE_EVENT = "Violet";
    private static final String COLOR_FLOW_ACTUAL_CLASS = "Olive";

    private String classDiagramSvgFilename;

    private final Map<KeyRelation.Type, String> connections;
    private Set<KeyElement.Type> ignored;
    private ReverseEngineeringModel model;
    private final StringBuilder content = new StringBuilder();
    private String header;
    private String footer = "generated by DDDplus";
    private String title;
    private Direction direction;
    private Set<String> skinParams = new HashSet<>();
    private Set<String> notes = new TreeSet<>();
    private boolean showNotLabeledElements = false;
    private boolean showCoverage = true;

    public PlantUmlRenderer() {
        connections = new HashMap<>();
        connections.put(KeyRelation.Type.Union, "x--x");

        connections.put(KeyRelation.Type.HasOne, escape("1") + " *-- " + escape("1"));
        connections.put(KeyRelation.Type.HasMany, escape("1") + " *-- " + escape("N"));
        connections.put(KeyRelation.Type.BelongTo, "--|>");
        connections.put(KeyRelation.Type.Associate, "o--");

        connections.put(KeyRelation.Type.Many2Many, "--");
        connections.put(KeyRelation.Type.Contextual, "--|>");
        connections.put(KeyRelation.Type.From, "-->");
        connections.put(KeyRelation.Type.Extends, "--|>");
        connections.put(KeyRelation.Type.Implements, "..|>");

        boolean lineDefined = false;
        for (KeyRelation.Type type : KeyRelation.Type.values()) {
            if (connections.containsKey(type)) {
                lineDefined = true;
                break;
            }
        }
        if (!lineDefined) {
            throw new RuntimeException("KeyRelation.Type missing line definition");
        }
    }

    public PlantUmlRenderer classDiagramSvgFilename(String classDiagramSvgFilename) {
        this.classDiagramSvgFilename = classDiagramSvgFilename;
        return this;
    }

    public PlantUmlRenderer showNotLabeledElements() {
        this.showNotLabeledElements = true;
        return this;
    }

    public PlantUmlRenderer disableCoverage() {
        this.showCoverage = false;
        return this;
    }

    public String umlContent() {
        if (model == null) {
            throw new IllegalArgumentException("call build before this");
        }

        return content.toString();
    }

    @Override
    public PlantUmlRenderer build(ReverseEngineeringModel model) {
        return build(model, Sets.newHashSet());
    }

    public PlantUmlRenderer build(ReverseEngineeringModel model, Set<KeyElement.Type> ignored) {
        this.model = model;
        this.ignored = ignored;

        start().appendDirection().appendSkinParam().appendTitle();

        if (showCoverage) {
            appendHeader();
        }

        //addClassMethodReport();
        addNotes();

        // aggregates
        append("package 逆向业务模型 {").append(NEWLINE);
        model.aggregates().forEach(a -> addAggregate(a));
        append(BRACE_CLOSE).append(NEWLINE);

        //addSimilarities();
        addKeyUsecases();
        addOrphanKeyFlows();
        addKeyRelations();
        addKeyEvents();

        appendFooter().end();
        return this;
    }

    @Override
    public void render() throws IOException {
        SourceStringReader reader = new SourceStringReader(content.toString());
        final ByteArrayOutputStream os = new ByteArrayOutputStream();
        reader.generateImage(os, new FileFormatOption(FileFormat.SVG));
        try (OutputStream outputStream = new FileOutputStream(this.classDiagramSvgFilename)) {
            os.writeTo(outputStream);
            os.close();
        }
    }

    private PlantUmlRenderer addNotes() {
        if (notes.isEmpty()) {
            return this;
        }

        append("note as Legend").append(NEWLINE);
        for (String note : notes) {
            append(TAB).append(note).append(NEWLINE);
        }
        append("end note").append(NEWLINE).append(NEWLINE);
        return this;
    }

    private PlantUmlRenderer addClassMethodReport() {
        ClassMethodReport report = model.getClassMethodReport();
        append("note as ClassMethodReportNote").append(NEWLINE);
        append(String.format("  Class: annotated(%d) public(%d) deprecated(%d)",
                model.annotatedModels(),
                report.getClassInfo().getPublicClasses().size(),
                report.getClassInfo().getDeprecatedClasses().size()
                )).append(NEWLINE);
        append(String.format("  Method: annotated(%d) public(%d) default(%d) private(%d) protected(%d) static(%d) deprecated(%d)",
                model.annotatedMethods(),
                report.getMethodInfo().getPublicMethods().size(),
                report.getMethodInfo().getDefaultMethods().size(),
                report.getMethodInfo().getPrivateMethods().size(),
                report.getMethodInfo().getProtectedMethods().size(),
                report.getMethodInfo().getStaticMethods().size(),
                report.getMethodInfo().getDeprecatedMethods().size()
                )).append(NEWLINE);
        append(String.format("  Statements: %d", report.getStatementN()))
                .append(NEWLINE);
        append("end note").append(NEWLINE).append(NEWLINE);
        return this;
    }

    private PlantUmlRenderer start() {
        append(STARTUML).append(NEWLINE).append(NEWLINE);
        return this;
    }

    private PlantUmlRenderer end() {
        append(NEWLINE).append(ENDUML);
        return this;
    }

    private String escape(String value) {
        return QUOTE + value + QUOTE;
    }

    private PlantUmlRenderer writeClazzDefinition(KeyEventEntry entry) {
        append("class ").append(entry.getClassName());
        String tag = "E";
        append(String.format(" <<(E,#9197DB) %s: %s>> ", tag, entry.getJavadoc()));
        append(" {").append(NEWLINE);
        if (entry.orphaned()) {
            append(TAB).append("未标注生产者").append(NEWLINE);
        }
        if (entry.hasRemark()) {
            append(TAB).append(entry.getRemark()).append(NEWLINE);
        }
        append(TAB).append("}").append(NEWLINE);
        return this;
    }

    private PlantUmlRenderer writeOrphanFlowClazzDefinition(String actor) {
        if (model.getKeyModelReport().containsActor(actor)) {
            return this;
        }

        List<KeyFlowEntry> orphanFlowsOfActor = model.getKeyFlowReport().orphanFlowsOfActor(actor);
        if (orphanFlowsOfActor.isEmpty()) {
            return this;
        }

        append("class ").append(actor);
        append(" {").append(NEWLINE);
        for (KeyFlowEntry entry : orphanFlowsOfActor) {
            append(TAB);
            append(entry, null);
            append(NEWLINE);
        }
        append(TAB).append("}").append(NEWLINE);
        return this;
    }

    private PlantUmlRenderer writeKeyUsecaseClazzDefinition(String actor) {
        append("class ").append(actor);
        append(" {").append(NEWLINE);
        for (KeyUsecaseEntry entry : model.getKeyUsecaseReport().actorKeyUsecases(actor)) {
            append("    {method} ");
            if (!entry.displayOut().isEmpty()) {
                append(entry.displayOut()).append(SPACE);
            }
            if (entry.isConsumer()) {
                // TODO
            }
            append(entry.displayNameWithRemark())
                    .append(BRACKET_OPEN)
                    .append(entry.displayIn())
                    .append(BRACKET_CLOSE)
                    .append(SPACE)
                    .append(entry.getJavadoc())
                    .append(NEWLINE);
        }
        append(TAB).append("}").append(NEWLINE);
        return this;
    }

    private PlantUmlRenderer writeClazzDefinition(KeyModelEntry keyModelEntry, boolean isAggregateRoot) {
        append("class ").append(keyModelEntry.getClassName());
        if (isAggregateRoot) {
            if (keyModelEntry.hasJavadoc()) {
                append(String.format(" <<(R,#FF7700) %s>> ", keyModelEntry.getJavadoc()));
            } else {
                append(" <<(R,#FF7700)>> ");
            }
        } else if (keyModelEntry.isBehaviorOnly()) {
            if (keyModelEntry.hasJavadoc()) {
                append(String.format(" <<(B,#9197DB) %s>> ", keyModelEntry.getJavadoc()));
            } else {
                append(" <<(B,#9197DB)>> ");
            }
        } else {
            if (keyModelEntry.hasJavadoc()) {
                append(String.format(" <<%s>> ", keyModelEntry.getJavadoc()));
            }
        }
        append(" {").append(NEWLINE);
        if (!keyModelEntry.types().isEmpty()) {
            for (KeyElement.Type type : keyModelEntry.types()) {
                if (ignored.contains(type)) {
                    continue;
                }

                append(String.format("    __ %s __", type)).append(NEWLINE);
                append("    {field} ").append(keyModelEntry.displayFieldByType(type)).append(NEWLINE);
            }

            if (showNotLabeledElements && !keyModelEntry.undefinedTypes().isEmpty()) {
                append("    __ NotLabeled __").append(NEWLINE);
                append("    {field} ").append(keyModelEntry.displayUndefinedTypes()).append(NEWLINE);
            }
        }

        if (!keyModelEntry.getKeyRuleEntries().isEmpty()) {
            append("    __ 规则 __").append(NEWLINE);
            for (KeyRuleEntry entry : keyModelEntry.getKeyRuleEntries()) {
                append("    {method} ");
                append(entry.displayNameWithRemark())
                        .append(BRACKET_OPEN)
                        .append(entry.displayRefer())
                        .append(BRACKET_CLOSE)
                        .append(SPACE)
                        .append(entry.getJavadoc())
                        .append(NEWLINE);
            }
        }

        if (!keyModelEntry.getKeyBehaviorEntries().isEmpty()) {
            append("    __ 行为 __").append(NEWLINE);
            for (KeyBehaviorEntry entry : keyModelEntry.getKeyBehaviorEntries()) {
                append(TAB);
                if (entry.isAsync()) {
                    append(" {abstract} ");
                }
                append(" {method} ");
                append(entry.displayNameWithRemark())
                        .append(BRACKET_OPEN)
                        .append(entry.displayArgs())
                        .append(BRACKET_CLOSE)
                        .append(SPACE)
                        .append(entry.getJavadoc());
                if (entry.produceEvent()) {
                    append(MessageFormat.format(COLOR_TMPL_OPEN, COLOR_BEHAVIOR_PRODUCE_EVENT));
                    append(" -> ").append(entry.displayEvents()).append(SPACE);
                    append(COLOR_TMPL_CLOSE);
                }
                append(NEWLINE);
            }
        }

        if (!keyModelEntry.getKeyFlowEntries().isEmpty()) {
            append("    __ 流程 __").append(NEWLINE);
            for (KeyFlowEntry entry : keyModelEntry.getKeyFlowEntries()) {
                append(TAB);
                append(entry, keyModelEntry);
                append(NEWLINE);
            }
        }

        append(TAB).append("}").append(NEWLINE);
        // the note
        if (false && keyModelEntry.hasJavadoc()) {
            append("note left: " + keyModelEntry.getJavadoc()).append(NEWLINE);
        }

        return this;
    }

    private void append(KeyFlowEntry entry, KeyModelEntry keyModelEntry) {
        if (entry.isAsync()) {
            append(" {abstract} ");
        }
        if (entry.isPolymorphism()) {
            append(" {static} ");
        }
        append(" {method} ");
        append(entry.getMethodName())
                .append(BRACKET_OPEN)
                .append(entry.displayEffectiveArgs())
                .append(BRACKET_CLOSE)
                .append(SPACE)
                .append(entry.getJavadoc());
        if (keyModelEntry != null && !keyModelEntry.getClassName().equals(entry.displayActualClass())) {
            append(SPACE)
                    .append(MessageFormat.format(COLOR_TMPL_OPEN, COLOR_FLOW_ACTUAL_CLASS))
                    .append(entry.displayActualClass()).append(SPACE)
                    .append(COLOR_TMPL_CLOSE);
        }
        if (entry.produceEvent()) {
            append(MessageFormat.format(COLOR_TMPL_OPEN, COLOR_BEHAVIOR_PRODUCE_EVENT));
            append(" -> ").append(entry.displayEvents()).append(SPACE);
            append(COLOR_TMPL_CLOSE);
        }
    }

    private PlantUmlRenderer append(String s) {
        if (s != null) {
            content.append(s);
        }
        return this;
    }

    private PlantUmlRenderer appendHeader() {
        append("header").append(NEWLINE);
        if (header != null && !header.isEmpty()) {
            append(header).append(NEWLINE);
        }
        CoverageReport report = model.coverageReport();
        append(String.format("公共类：%d，标注：%d，覆盖率：%.1f%%", report.getPublicClazzN(), report.getAnnotatedClazzN(), report.clazzCoverage()));
        append(NEWLINE);
        append(String.format("公共方法：%d，标注：%d，覆盖率：%.1f%%", report.getPublicMethodN(), report.getAnnotatedMethodN(), report.methodCoverage()));
        append(NEWLINE);
        append(String.format("字段属性：%d，标注：%d，覆盖率：%.1f%%", report.getPropertyN(), report.getAnnotatedPropertyN(), report.propertyCoverage()));
        append(NEWLINE);
        append("endheader").append(NEWLINE).append(NEWLINE);
        return this;
    }

    public PlantUmlRenderer appendNote(String note) {
        notes.add(note);
        return this;
    }

    private PlantUmlRenderer appendSkinParam() {
        if (!skinParams.isEmpty()) {
            for (String skin : skinParams) {
                append("skinparam").append(SPACE).append(skin).append(NEWLINE);
            }
            append(NEWLINE);
        }
        return this;
    }

    private PlantUmlRenderer appendDirection() {
        if (direction != null) {
            switch (direction) {
                case LeftToRight:
                    append(DIRECTION_LEFT_RIGHT).append(NEWLINE);
                    break;
                case TopToBottom:
                    append(DIRECTION_TOP_BOTTOM).append(NEWLINE);
                    break;
            }
            append(NEWLINE);
        }
        return this;
    }

    private PlantUmlRenderer appendTitle() {
        if (title != null && !title.isEmpty()) {
            append("title").append(SPACE).append(title)
                    .append(NEWLINE).append(NEWLINE);
        }
        return this;
    }

    private PlantUmlRenderer appendFooter() {
        if (footer != null && !footer.isEmpty()) {
            append("footer").append(NEWLINE).append(footer).append(NEWLINE)
                    .append("endfooter").append(NEWLINE).append(NEWLINE);
        }
        return this;
    }

    private PlantUmlRenderer addAggregate(AggregateEntry aggregate) {
        append(MessageFormat.format(PACKAGE_TMPL, "Aggregate：" + aggregate.getName(), aggregate.getPackageName()));
        append(SPACE).append(BRACE_OPEN).append(NEWLINE);
        for (KeyModelEntry clazz : aggregate.keyModels()) {
            append(TAB).writeClazzDefinition(clazz, aggregate.isRoot(clazz)).append(NEWLINE);
        }
        append(BRACE_CLOSE);
        append(NEWLINE).append(NEWLINE);

        return this;
    }

    private PlantUmlRenderer addSimilarities() {
        append(MessageFormat.format(PACKAGE_TMPL, "相似度", "%"));
        append(SPACE).append(BRACE_OPEN).append(NEWLINE);
        for (SimilarityEntry entry : model.sortedSimilarities()) {
            append(TAB).append(entry.getLeftClass()).append(" .. ").append(entry.getRightClass())
                    .append(": ").append(String.format("%.0f", entry.getSimilarity()))
                    .append(NEWLINE);
        }
        append(BRACE_CLOSE);
        append(NEWLINE).append(NEWLINE);

        return this;
    }

    private PlantUmlRenderer addKeyRelations() {
        for (KeyRelationEntry entry : model.getKeyRelationReport().getRelationEntries()) {
            append(entry.getLeftClass())
                    .append(SPACE).append(connections.get(entry.getType())).append(SPACE)
                    .append(entry.getRightClass())
                    .append(": ").append(entry.getType().toString());
            String remark = entry.displayRemark();
            if (!remark.isEmpty()) {
                append("/").append(remark);
            }
            append(NEWLINE);
        }
        append(NEWLINE);
        return this;
    }

    private PlantUmlRenderer addKeyEvents() {
        if (model.getKeyEventReport().isEmpty()) {
            return this;
        }

        append(MessageFormat.format(PACKAGE_TMPL, "领域事件", "events"));
        append(SPACE).append(BRACE_OPEN).append(NEWLINE);
        for (KeyEventEntry entry : model.getKeyEventReport().getEvents()) {
            append(TAB).writeClazzDefinition(entry).append(NEWLINE);
        }

        append(BRACE_CLOSE);
        append(NEWLINE).append(NEWLINE);

        return this;
    }

    private PlantUmlRenderer addOrphanKeyFlows() {
        if (!model.getKeyFlowReport().hasOrphanFlowEntries()) {
            return this;
        }

        append(MessageFormat.format(PACKAGE_TMPL, "跨聚合复杂流程", "Orphan Services"));
        append(SPACE).append(BRACE_OPEN).append(NEWLINE);
        for (String actor : model.getKeyFlowReport().actors()) {
            append(TAB).writeOrphanFlowClazzDefinition(actor).append(NEWLINE);
        }

        append(BRACE_CLOSE);
        append(NEWLINE).append(NEWLINE);

        return this;
    }

    private PlantUmlRenderer addKeyUsecases() {
        if (model.getKeyUsecaseReport().getData().isEmpty()) {
            return this;
        }

        append(MessageFormat.format(PACKAGE_TMPL, "交互", "UseCase"));
        append(SPACE).append(BRACE_OPEN).append(NEWLINE);
        for (String actor : model.getKeyUsecaseReport().getData().keySet()) {
            append(TAB).writeKeyUsecaseClazzDefinition(actor).append(NEWLINE);
        }

        append(BRACE_CLOSE);
        append(NEWLINE).append(NEWLINE);
        return this;
    }

    public PlantUmlRenderer header(String header) {
        this.header = header;
        return this;
    }

    public PlantUmlRenderer footer(String footer) {
        this.footer = footer;
        return this;
    }

    public PlantUmlRenderer direction(Direction direction) {
        this.direction = direction;
        return this;
    }

    /**
     * 增加(可以多次调用)皮肤控制.
     *
     * <ul>Examples:
     * <li>linetype polyline</li>
     * <li>linetype ortho</li>
     * <li>handwritten true</li>
     * <li>monochrome true</li>
     * <li>nodesep 5</li>
     * <li>ranksep 8</li>
     * </ul>
     *
     * @see <a href="https://plantuml.com/en/skinparam">skinparam reference</a>
     */
    public PlantUmlRenderer skinParam(String skinParam) {
        this.skinParams.add(skinParam);
        return this;
    }

    public PlantUmlRenderer skinParamPolyline() {
        this.skinParam("linetype polyline");
        return this;
    }

    public PlantUmlRenderer skinParamOrtholine() {
        this.skinParam("linetype ortho");
        return this;
    }

    public PlantUmlRenderer skipParamHandWrittenStyle() {
        this.skinParam("handwritten true");
        return this;
    }

    public PlantUmlRenderer title(String title) {
        this.title = title;
        return this;
    }

}
