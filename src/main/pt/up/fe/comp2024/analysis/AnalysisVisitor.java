package pt.up.fe.comp2024.analysis;

import pt.up.fe.comp.jmm.analysis.table.Symbol;
import pt.up.fe.comp.jmm.analysis.table.SymbolTable;
import pt.up.fe.comp.jmm.analysis.table.Type;
import pt.up.fe.comp.jmm.ast.JmmNode;
import pt.up.fe.comp.jmm.ast.PreorderJmmVisitor;
import pt.up.fe.comp.jmm.report.Report;
import pt.up.fe.comp.jmm.report.Stage;
import pt.up.fe.comp2024.ast.NodeUtils;
import pt.up.fe.comp2024.ast.TypeUtils;

import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public abstract class AnalysisVisitor extends PreorderJmmVisitor<SymbolTable, Void> implements AnalysisPass {

    private List<Report> reports;

    public AnalysisVisitor() {
        reports = new ArrayList<>();
        setDefaultValue(() -> null);
    }

    protected void addReport(Report report) {
        reports.add(report);
    }

    protected List<Report> getReports() {
        return reports;
    }


    @Override
    public List<Report> analyze(JmmNode root, SymbolTable table) {
        // Visit the node
        visit(root, table);

        // Return reports
        return getReports();
    }

    public Type getNodeType(JmmNode node, SymbolTable table) {
        String type = node.getKind();

        switch (type) {
            case "TrueLiteral", "FalseLiteral", "NotExpr":
                return new Type("boolean", false);
            case "IntegerLiteral", "ArrayLengthExpr":
                return new Type("int", false);
            case "VarRefExpr", "LengthLiteral", "MainLiteral":
                String methodName = TypeUtils.getMethodName(node);
                return getVarType(node.get("name"), methodName, table);
            case "ThisLiteral":
                return new Type(table.getClassName(), false);
            case "NewClassObjExpr":
                return new Type(node.get("name"), false);
            case "NewArrayExpr":
                return new Type(node.get("name"), true);
            case "ArrayInitExpr":
                return new Type(getNodeType(node.getChildren().get(0), table).getName(), true);
            case "ArrayAccessExpr":
                String arrayType = getNodeType(node.getChildren().get(0), table).getName();
                return new Type(arrayType, false);
            case "MethodCallExpr", "MethodCall":
                return getReturnType(node, table);
            case "ReturnStmt":
                return getNodeType(node.getChild(0).getChild(0), table);
            case "BinaryExpr":
                String operator = node.get("op");
                checkOperation(node, table); // check if the operation is valid
                if (operator.equals("+") || operator.equals("-") || operator.equals("*") || operator.equals("/")) {
                    return new Type("int", false);
                } else {
                    return new Type("boolean", false);
                }
            case "ParenExpr":
                return getNodeType(node.getChildren().get(0), table);
            case "MethodDecl":
                return table.getReturnType(node.get("name"));
            default:
                return new Type("Unknown", false);
        }
    }

    public Void checkOperation(JmmNode node, SymbolTable table) {
        JmmNode left = node.getChildren().get(0);
        JmmNode right = node.getChildren().get(1);

        String operator = node.get("op");

        Type leftType = getNodeType(left, table);
        Type rightType = getNodeType(right, table);

        if (leftType == null || rightType == null) {
            return null;
        }

        if (!leftType.equals(rightType)) {
            addReport(Report.newError(
                    Stage.SEMANTIC,
                    NodeUtils.getLine(node),
                    NodeUtils.getColumn(node),
                    "Incompatible types in operation " + operator,
                    null)
            );
        }

        if (operator.equals("&&")) {
            if (!leftType.getName().equals("boolean") || !rightType.getName().equals("boolean")) {
                addReport(Report.newError(
                        Stage.SEMANTIC,
                        NodeUtils.getLine(node),
                        NodeUtils.getColumn(node),
                        "Incompatible types in logical operation " + operator,
                        null)
                );
            }
        } else if (operator.equals("+") || operator.equals("-") || operator.equals("*") || operator.equals("/")) {
            if (!leftType.getName().equals("int") || !rightType.getName().equals("int")) {
                addReport(Report.newError(
                        Stage.SEMANTIC,
                        NodeUtils.getLine(node),
                        NodeUtils.getColumn(node),
                        "Incompatible types in arithmetic operation " + operator,
                        null)
                );
            }
        }

        return null;
    }


    public Type getVarType(String varName, String methodName, SymbolTable table) {
        List<Symbol> args = table.getParameters(methodName);
        List<Symbol> locals = table.getLocalVariables(methodName);
        List<Symbol> globals = table.getFields();
        String extendsClass = table.getSuper();

        if (hasImport(varName, table)) {
            return new Type(varName, false);
        }

        if (extendsClass != null && extendsClass.equals(varName)) {
            return new Type(varName, false);
        }

        for (Symbol arg : args) {
            if (arg.getName().equals(varName)) {
                return arg.getType();
            }
        }

        for (Symbol local : locals) {
            if (local.getName().equals(varName)) {
                return local.getType();
            }
        }

        for (Symbol global : globals) {
            if (global.getName().equals(varName)) {
                return global.getType();
            }
        }

        return null;
    }

    public boolean hasImport(String className, SymbolTable table) {
        List<String> imports = table.getImports();
        return table.getImports().stream().map(imported -> imported.split(", ")[imported.split(",").length - 1]).anyMatch(imported -> imported.equals(className));

    }

    public Type getReturnType(JmmNode node, SymbolTable table) {
        if (node.getKind().equals("MethodCallExpr")) {
            // get last child
            return getReturnType(node.getChildren().get(node.getChildren().size() - 1), table);
        } else if (node.getKind().equals("MethodCall")) {
            // either it's in the table or accept if imported
            Type typeNode = getNodeType(node.getJmmParent().getChildren().get(0), table);
            if (typeNode == null) {
                return null;
            }
            if (table.getReturnType(node.get("name")) != null) {
                return table.getReturnType(node.get("name"));
            } else if (hasImport(typeNode.getName(), table)) {
                return new Type(typeNode.getName(), false);
            } else if (table.getSuper() != null) {
                String superClassName = table.getSuper();
                String className = table.getClassName();

                if (typeNode.getName().equals(superClassName) || typeNode.getName().equals(className)) {
                    return new Type(typeNode.getName(), false);
                }
            }

            addReport(Report.newError(
                    Stage.SEMANTIC,
                    NodeUtils.getLine(node),
                    NodeUtils.getColumn(node),
                    "Method " + node.get("name") + " is not declared",
                    null)
            );

            return null;
        }

        return null;
    }


}
