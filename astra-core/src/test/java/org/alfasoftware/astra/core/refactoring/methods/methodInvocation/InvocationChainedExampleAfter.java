package org.alfasoftware.astra.core.refactoring.methods.methodInvocation;

import org.alfasoftware.astra.exampleTypes.C;

public class InvocationChainedExampleAfter {

  @SuppressWarnings("unused")
  private void a() {
    C foo = new C();
    foo.third();
  }
}

