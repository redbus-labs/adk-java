package com.google.adk.agents;

import static com.google.adk.testing.TestUtils.createInvocationContext;
import static com.google.adk.testing.TestUtils.createTestAgentBuilder;
import static com.google.adk.testing.TestUtils.createTestLlm;
import static com.google.common.truth.Truth.assertThat;

import com.google.adk.models.LlmResponse;
import io.reactivex.rxjava3.core.Single;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class InstructionTest {

  @Test
  public void testCanonicalInstruction_staticInstruction() {
    String instruction = "Test static instruction";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .instruction(instruction)
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction = agent.canonicalInstruction(invocationContext).blockingGet();

    assertThat(canonicalInstruction).isEqualTo(instruction);
  }

  @Test
  public void testCanonicalInstruction_providerInstructionInjectsContext() {
    String instruction = "Test provider instruction for invocation: ";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .instruction(
                new Instruction.Provider(
                    context -> Single.just(instruction + context.invocationId())))
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction = agent.canonicalInstruction(invocationContext).blockingGet();

    assertThat(canonicalInstruction).isEqualTo(instruction + invocationContext.invocationId());
  }

  @Test
  public void testCanonicalGlobalInstruction_staticInstruction() {
    String instruction = "Test static global instruction";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .globalInstruction(instruction)
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction = agent.canonicalGlobalInstruction(invocationContext).blockingGet();

    assertThat(canonicalInstruction).isEqualTo(instruction);
  }

  @Test
  public void testCanonicalGlobalInstruction_providerInstructionInjectsContext() {
    String instruction = "Test provider global instruction for invocation: ";
    LlmAgent agent =
        createTestAgentBuilder(createTestLlm(LlmResponse.builder().build()))
            .globalInstruction(
                new Instruction.Provider(
                    context -> Single.just(instruction + context.invocationId())))
            .build();
    ReadonlyContext invocationContext = new ReadonlyContext(createInvocationContext(agent));

    String canonicalInstruction = agent.canonicalGlobalInstruction(invocationContext).blockingGet();

    assertThat(canonicalInstruction).isEqualTo(instruction + invocationContext.invocationId());
  }
}
