package org.eigenbase.rex;

import net.hydromatic.optiq.jdbc.JavaTypeFactoryImpl;

import org.apache.drill.common.expression.FunctionCall;
import org.apache.drill.common.expression.IfExpression;
import org.apache.drill.common.expression.LogicalExpression;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.expression.ValueExpressions.BooleanExpression;
import org.apache.drill.common.expression.ValueExpressions.DoubleExpression;
import org.apache.drill.common.expression.ValueExpressions.LongExpression;
import org.apache.drill.common.expression.ValueExpressions.QuotedString;
import org.apache.drill.common.expression.visitors.ExprVisitor;
import org.apache.drill.optiq.DrillParseContext;
import org.apache.drill.optiq.RelDataTypeDrillImpl;
import org.eigenbase.reltype.RelDataTypeFactoryImpl;
import org.eigenbase.rex.RexBuilder;
import org.eigenbase.rex.RexLiteral;
import org.eigenbase.rex.RexNode;
import org.eigenbase.sql.SqlFunctionCategory;
import org.eigenbase.sql.SqlIdentifier;
import org.eigenbase.sql.SqlOperator;
import org.eigenbase.sql.SqlSyntax;
import org.eigenbase.sql.fun.SqlStdOperatorTable;
import org.eigenbase.sql.parser.SqlParserPos;
import org.eigenbase.sql.type.SqlTypeFactoryImpl;
import org.eigenbase.sql.type.SqlTypeName;

import java.util.ArrayList;
import java.util.List;

public class LogicalExpressionToRex implements ExprVisitor<RexNode, Object, Exception>{

  private final RexBuilder rexBuilder;
  private final DrillParseContext drillParseContext;
  private final RelDataTypeDrillImpl relDataTypeDrill;

  public LogicalExpressionToRex(RexBuilder builder, DrillParseContext drillParseContext) {
    this.rexBuilder = builder;
    this.drillParseContext = drillParseContext;
    this.relDataTypeDrill = new RelDataTypeDrillImpl(new SqlTypeFactoryImpl());
  }

  @Override
  public RexNode visitFunctionCall(FunctionCall call, Object value)
      throws Exception {
    // iterate over the arguments
    List<RexNode> rexArgs = new ArrayList<>();
    for ( LogicalExpression logEx : call){
      rexArgs.add(logEx.accept(this, value));
    }
    SqlOperator op = null;
    SqlIdentifier identifier =
        new SqlIdentifier(call.getDefinition().getName().toUpperCase(),
                          new SqlParserPos(0, call.getPosition().getCharIndex()));
    List<SqlOperator> matches;
    // TODO - I had some trouble navigating optiq to figure out exactly where the SqlSyntax enum
    // is meaningful. I assumed it was only for validation or precedence determination for
    // different syntaxes of a given function, which is already handled by Drill
    // this means of converting Drill calls into SqlOperators assumes that the implementations
    // of the functions is the same once they have entered the parse tree (thus there is no need to
    // add a notion of the different syntax tracking to the drill parser)
    for (SqlSyntax syntax : SqlSyntax.values()){
      matches = SqlStdOperatorTable.instance().lookupOperatorOverloads(
          identifier,
          SqlFunctionCategory.Numeric, // this is currently being ignored in the lookup
          syntax);
      if (matches.size() > 0){
        op = matches.get(0);
        break;
      }
    }
    return new RexCall(relDataTypeDrill, op, rexArgs);

  }

  @Override
  public RexNode visitIfExpression(IfExpression ifExpr, Object value)
      throws Exception {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public RexNode visitSchemaPath(SchemaPath path, Object value)
      throws Exception {
    return new RexInputRef(relDataTypeDrill.getField(path.getPath().toString(), true).getIndex(), relDataTypeDrill );
  }

  @Override
  public RexNode visitLongConstant(LongExpression intExpr, Object value)
      throws Exception {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public RexNode visitDoubleConstant(DoubleExpression dExpr, Object value)
      throws Exception {
    return rexBuilder.makeLiteral(new Double(dExpr.getDouble()),
        ((JavaTypeFactoryImpl)rexBuilder.getTypeFactory()).createType(Double.class),
        SqlTypeName.DOUBLE);
  }

  @Override
  public RexNode visitBooleanConstant(BooleanExpression e, Object value)
      throws Exception {
    return rexBuilder.makeLiteral(new Boolean(e.getBoolean()),
        ((JavaTypeFactoryImpl)rexBuilder.getTypeFactory()).createType(Boolean.class),
        SqlTypeName.BOOLEAN);
  }

  @Override
  public RexNode visitQuotedStringConstant(QuotedString e, Object value)
      throws Exception {
    return rexBuilder.makeLiteral(e.value,
        ((JavaTypeFactoryImpl)rexBuilder.getTypeFactory()).createType(String.class),
        SqlTypeName.CHAR);
  }

  @Override
  public RexNode visitUnknown(LogicalExpression e, Object value)
      throws Exception {
    // TODO Auto-generated method stub
    return null;
  }

}
