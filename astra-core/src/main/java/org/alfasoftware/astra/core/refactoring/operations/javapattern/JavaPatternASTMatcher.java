package org.alfasoftware.astra.core.refactoring.operations.javapattern;

import org.eclipse.jdt.core.dom.ASTMatcher;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;



public class JavaPatternASTMatcher {

  private final Collection<MethodDeclaration> substituteMethods;
  private final Collection<JavaPatternFileParser.SingleASTNodePatternMatcher> javaPatternsToMatch;
  private final Collection<ASTNodeMatchInformation> foundMatches = new ArrayList<>();

  public JavaPatternASTMatcher(Collection<JavaPatternFileParser.SingleASTNodePatternMatcher> javaPatternsToMatch, Collection<MethodDeclaration> substituteMethods) {
    this.javaPatternsToMatch = javaPatternsToMatch;
    this.substituteMethods = substituteMethods;
  }

  /**
   * Iterates over the {@link JavaPattern}s specified and captures the match information
   * @param matchCandidate the ASTNode we are testing for a match
   * @return true if any matches are found
   */
  boolean matchAndCapture(ASTNode matchCandidate){
    for (JavaPatternFileParser.SingleASTNodePatternMatcher javaPatternToMatch: javaPatternsToMatch) {
      final JavaPatternMatcher javaPatternMatcher = new JavaPatternMatcher(javaPatternToMatch);
      if(javaPatternMatcher.match(javaPatternToMatch.getJavaPatternToMatch(), matchCandidate)) {
        foundMatches.add(javaPatternMatcher.getNodeMatch());
      }
    }
    return !foundMatches.isEmpty();
  }

  /**
   * Returns any found matches and the captured information
   * @return the matches found and their captured information
   */
  public Collection<ASTNodeMatchInformation> getFoundMatches() {
    return foundMatches;
  }

  /**
   * This ASTMatcher sub-type has support for matching a Pattern against a single ASTNode
   * For matches found it collects the match information expressed by the @JavaPattern's method parameters and the @Substitute methods.
   */
  class JavaPatternMatcher extends ASTMatcher {
    private final Map<String, ASTNode> substituteMethodToCapturedNode = new HashMap<>();
    private final Map<String, ASTNode> simpleNameToCapturedNode = new HashMap<>();
    private final JavaPatternFileParser.SingleASTNodePatternMatcher patternToMatch;
    private final Map<String, String> simpleTypeToCapturedType = new HashMap<>();
    private ASTNode astNodeToMatchAgainst;

    public JavaPatternMatcher(JavaPatternFileParser.SingleASTNodePatternMatcher patternToMatch) {
      this.patternToMatch = patternToMatch;
    }

    /**
     * Overridden matcher for simpleName.
     *
     * If the simpleName is one of the parameters specified for the {@link JavaPattern} annotated method, this
     * - Checks that the type of the simpleName and the type resolved for the matchCandidate are compatible. They are considered a match if
     * -- they resolve to the same type
     * -- the match candidate resolves to a type which is a subtype of the simpleName
     * -- the simpleName we are matching is a TypeVariable
     *
     * If we have a match
     * - capture the matchCandidate ASTNode against the simpleName
     * - capture any TypeArguments from the matchCandidate
     * - return true to show that we have a match, and exit early from the matcher for this tree.
     *
     * If the simpleName is not one of the specified parameters we don't care about the name.
     *
     * @param simpleNameFromPatternMatcher the simpleName from the {@link JavaPattern} we are trying to match
     * @param matchCandidate the ASTNode we are testing for a match
     * @return true if the matchCandidate is considered a match for the simpleName that comes from the {@link JavaPattern}
     */
    @Override
    public boolean match(SimpleName simpleNameFromPatternMatcher, Object matchCandidate) {
      final Optional<SingleVariableDeclaration> patternParameter = patternToMatch.getSingleVariableDeclarations().stream()
          .filter(singleVariableDeclaration -> singleVariableDeclaration.getName().toString().equals(simpleNameFromPatternMatcher.toString()))
          .findAny();

      if(patternParameter.isPresent() &&
          (isSubTypeCompatible(simpleNameFromPatternMatcher, (Expression) matchCandidate) ||
              typeOfSimpleNameIsEqual(simpleNameFromPatternMatcher, (Expression) matchCandidate) ||
              simpleNameFromPatternMatcher.resolveTypeBinding().isTypeVariable())) { // this should be more specific. Not just a type variable, but needs to match what the captured type is if there is one.
        // we may need to resolve Type variables defined in the JavaPattern
        if(simpleNameFromPatternMatcher.resolveTypeBinding().isParameterizedType()) {
          final ITypeBinding[] matchCandidateTypeParameters = ((Expression) matchCandidate).resolveTypeBinding().getTypeArguments();
          final ITypeBinding[] simpleTypesToMatch = simpleNameFromPatternMatcher.resolveTypeBinding().getTypeArguments();
          for (int i = 0; i < simpleTypesToMatch.length; i++) {
            if(weAlreadyHaveACapturedTypeForThisSimpleTypeWhichIsDifferent(matchCandidateTypeParameters[i], simpleTypesToMatch[i])){
              return false;
            }
            simpleTypeToCapturedType.put(simpleTypesToMatch[i].getName(), matchCandidateTypeParameters[i].getName());
          }
        }
        return putSimpleNameAndCapturedNode(simpleNameFromPatternMatcher, (ASTNode) matchCandidate);
      } else {
        return true; // the names given to variables in the pattern don't matter.
      }
    }

    private boolean putSimpleNameAndCapturedNode(SimpleName simpleNameFromPatternMatcher, ASTNode matchCandidate) {
      if(simpleNameToCapturedNode.get(simpleNameFromPatternMatcher.toString()) != null &&
      !simpleNameToCapturedNode.get(simpleNameFromPatternMatcher.toString()).subtreeMatch(new ASTMatcher(), matchCandidate)){
        return false;
      } else {
        simpleNameToCapturedNode.put(simpleNameFromPatternMatcher.toString(), matchCandidate);
        return true;
      }
    }

    private boolean weAlreadyHaveACapturedTypeForThisSimpleTypeWhichIsDifferent(ITypeBinding matchCandidateTypeParameter, ITypeBinding simpleTypesToMatch) {
      return simpleTypeToCapturedType.get(simpleTypesToMatch.getName()) != null
          && !simpleTypeToCapturedType.get(simpleTypesToMatch.getName()).equals(matchCandidateTypeParameter.getName());
    }

    private boolean typeOfSimpleNameIsEqual(SimpleName simpleNameFromPatternMatcher, Expression matchCandidate) {
      return simpleNameFromPatternMatcher.resolveTypeBinding().getTypeDeclaration()
          .isEqualTo(matchCandidate.resolveTypeBinding().getTypeDeclaration());
    }

    /**
     * Checks whether the TypeDeclaration for the resolved type binding for the simpleName and the matchCandidate are sub-type compatible.
     * The TypeDeclaration for the resolved type binding will be the generic version of the Type, if it is parameterised.
     * For example, Map<String, Integer> will return Map<K,V>, allowing us to match for example a matchCandidate which resovles to HashMap<String,Integer> to
     * a generic Map.
     */
    private boolean isSubTypeCompatible(SimpleName simpleNameFromPatternMatcher, Expression matchCandidate) {
      return matchCandidate.resolveTypeBinding().getTypeDeclaration().isSubTypeCompatible(simpleNameFromPatternMatcher.resolveTypeBinding().getTypeDeclaration());
    }

    /**
     * Tests whether a MethodInvocation in the {@link JavaPattern} matches a given ASTNode.
     * If the MethodInvocation from the {@link JavaPattern} is an invocation of a {@link Substitute} annotated method,
     * verify that the matchCandidate is appropriate for the substitute and capture it.
     * If the MethodInvocation is not from a {@link Substitute} annotated method, delegate to the default matching.
     */
    @Override
    public boolean match(MethodInvocation methodInvocationFromJavaPattern, Object matchCandidate) {
      if(methodInvocationMatchesSubstituteMethod(methodInvocationFromJavaPattern)) { // TODO investigate whether this handling of methods is adequate, and whether we need similar matches for other methodinvocationlikes, such as InfixExpression
        if (matchCandidate instanceof MethodInvocation &&
            returnTypeMatches(methodInvocationFromJavaPattern, (MethodInvocation) matchCandidate) &&
            safeSubtreeListMatch(methodInvocationFromJavaPattern.arguments(), ((MethodInvocation) matchCandidate).arguments())) {
          return putSubstituteNameAndCapturedNode(methodInvocationFromJavaPattern,  (ASTNode) matchCandidate);
        }
        return true;
      } else {
        return super.match(methodInvocationFromJavaPattern, matchCandidate);
      }
    }

    private boolean returnTypeMatches(MethodInvocation methodInvocationFromJavaPattern, MethodInvocation matchCandidate) {
      final ITypeBinding returnType = methodInvocationFromJavaPattern.resolveMethodBinding().getReturnType();
      if(returnType.isTypeVariable() && simpleTypeToCapturedType.get(returnType.getName()) != null) {
        return simpleTypeToCapturedType.get(returnType.getName()).equals(matchCandidate.resolveMethodBinding().getReturnType().getName());
      } else if (returnType.isTypeVariable() && simpleTypeToCapturedType.get(returnType.getName()) == null){
        simpleTypeToCapturedType.put(returnType.getName(), matchCandidate.resolveMethodBinding().getReturnType().getName());
        return true;
      } else {
        return returnType.isEqualTo(matchCandidate.resolveMethodBinding().getReturnType());
      }
    }

    private boolean putSubstituteNameAndCapturedNode(MethodInvocation methodInvocationFromJavaPattern, ASTNode matchCandidate) {
      if(substituteMethodToCapturedNode.get(methodInvocationFromJavaPattern.toString()) != null &&
          !substituteMethodToCapturedNode.get(methodInvocationFromJavaPattern.toString()).subtreeMatch(new ASTMatcher(), matchCandidate)){
        return false;
      } else {
        substituteMethodToCapturedNode.put(methodInvocationFromJavaPattern.getName().toString(),  matchCandidate);
        return true;
      }
    }

    /**
     * Overridden match to be more relaxed about static arguments.
     */
    @Override
    public boolean match(QualifiedName node, Object other) {
      if(other instanceof SimpleName) {
        final IBinding iBinding = ((SimpleName) other).resolveBinding();
        if(iBinding.isEqualTo(node.resolveBinding())){
          return true;
        }
      }
      if (!(other instanceof QualifiedName)) {
        return false;
      }
      QualifiedName o = (QualifiedName) other;
      return safeSubtreeMatch(node.getQualifier(), o.getQualifier())
          && safeSubtreeMatch(node.getName(), o.getName());
    }

    @Override
    public boolean match(SimpleType node, Object other) {
      if (!(other instanceof SimpleType)) {
        return false;
      }
      SimpleType o = (SimpleType) other;

      if(node.resolveBinding().isTypeVariable() &&
          weAlreadyHaveACapturedTypeForThisSimpleTypeWhichIsDifferent(o.resolveBinding(), node.resolveBinding())) {
        return false;
      } else if (node.resolveBinding().isTypeVariable()) {
        simpleTypeToCapturedType.put(node.resolveBinding().getName(), o.resolveBinding().getName());
        return true;
      } else {
        return super.match(node, other);
      }
    }

    /**
     *
     * @param o the methodinvocation to test
     * @return true, if the method invocation matches the declaration of an @Substitute annotated method
     */
    private boolean methodInvocationMatchesSubstituteMethod(MethodInvocation o) {
      return substituteMethods.stream().anyMatch(methodDeclaration ->
              o.resolveMethodBinding().getMethodDeclaration().isEqualTo(methodDeclaration.resolveBinding()));
    }


    /**
     * Entry point for testing whether a JavaPattern matches a matchCandidate ASTNode
     */
    public boolean match(ASTNode javaPattern, Object matchCandidate) {
      astNodeToMatchAgainst = (ASTNode) matchCandidate;
      return javaPattern.subtreeMatch(this, matchCandidate);
    }

    ASTNodeMatchInformation getNodeMatch(){
      return new ASTNodeMatchInformation(astNodeToMatchAgainst, substituteMethodToCapturedNode, simpleNameToCapturedNode, simpleTypeToCapturedType);
    }
  }

}
