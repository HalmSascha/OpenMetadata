/*
 *  Copyright 2026 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.service.datacontract.odcs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.openmetadata.schema.tests.TestCase;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.Include;
import org.openmetadata.service.datacontract.odcs.ODCSRuleOutcome.TestCaseOutcome;
import org.openmetadata.service.datacontract.odcs.ODCSRuleOutcome.UnsupportedOutcome;
import org.openmetadata.service.exception.BadRequestException;
import org.openmetadata.service.exception.EntityNotFoundException;
import org.openmetadata.service.jdbi3.TestCaseRepository;
import org.openmetadata.service.resources.dqtests.TestCaseMapper;

/**
 * Creates, or updates, the test cases {@link ODCSQualityRuleMapper} described. A test case with the
 * same name may already exist on the table. When the contract already owns it, or an earlier ODCS
 * import created it, it is updated in place. When it belongs to someone else it is never
 * overwritten: it is linked as it is if it runs the same test definition, and the rule is skipped
 * otherwise.
 */
@Slf4j
public final class ODCSTestCaseMaterializer {
  private static final String TEST_DEFINITION_FIELD = "testDefinition";

  /** Authorizes writing one test case before it is persisted; throws when the caller may not. */
  @FunctionalInterface
  public interface WriteGuard {
    void authorize(TestCase testCase, boolean overwritesExisting);
  }

  /**
   * @param ownedTestCaseIds test cases the contract already links, which the import may update
   */
  public record Request(
      List<TestCaseOutcome> outcomes, Set<UUID> ownedTestCaseIds, WriteGuard guard, String user) {}

  /** Test cases to link to the contract, and the rules whose test case could not be written. */
  public record Result(List<EntityReference> testCases, List<UnsupportedOutcome> skipped) {}

  private final TestCaseRepository repository;
  private final TestCaseMapper mapper = new TestCaseMapper();

  public ODCSTestCaseMaterializer(TestCaseRepository repository) {
    this.repository = repository;
  }

  public Result materialize(Request request) {
    List<EntityReference> testCases = new ArrayList<>();
    List<UnsupportedOutcome> skipped = new ArrayList<>();
    for (TestCaseOutcome outcome : request.outcomes()) {
      try {
        resolve(outcome, request)
            .ifPresentOrElse(testCases::add, () -> skipped.add(nameTakenOutcome(outcome)));
      } catch (IllegalArgumentException | BadRequestException | EntityNotFoundException e) {
        LOG.debug("Test case for ODCS rule '{}' was rejected", outcome.rule().getName(), e);
        skipped.add(new UnsupportedOutcome(outcome.rule(), rejectedReason(e)));
      }
    }
    return new Result(List.copyOf(testCases), List.copyOf(skipped));
  }

  /**
   * What {@link #materialize} would skip because a different test case already has the name,
   * without writing anything.
   */
  public List<UnsupportedOutcome> conflicts(Request request) {
    List<UnsupportedOutcome> conflicts = new ArrayList<>();
    for (TestCaseOutcome outcome : request.outcomes()) {
      TestCase testCase = toTestCase(outcome, request);
      TestCase existing = findExisting(testCase.getFullyQualifiedName());
      if (isOwnedBySomeoneElse(existing, request)
          && linkIfSameDefinition(existing, testCase).isEmpty()) {
        conflicts.add(nameTakenOutcome(outcome));
      }
    }
    return List.copyOf(conflicts);
  }

  private Optional<EntityReference> resolve(TestCaseOutcome outcome, Request request) {
    TestCase testCase = toTestCase(outcome, request);
    TestCase existing = findExisting(testCase.getFullyQualifiedName());
    return isOwnedBySomeoneElse(existing, request)
        ? linkIfSameDefinition(existing, testCase)
        : Optional.of(write(testCase, existing != null, request));
  }

  private TestCase toTestCase(TestCaseOutcome outcome, Request request) {
    TestCase testCase = mapper.createToEntity(outcome.testCase(), request.user());
    repository.setFullyQualifiedName(testCase);
    return testCase;
  }

  private EntityReference write(TestCase testCase, boolean overwritesExisting, Request request) {
    request.guard().authorize(testCase, overwritesExisting);
    repository.prepare(testCase, false);
    return repository
        .createOrUpdate(null, testCase, request.user())
        .getEntity()
        .getEntityReference();
  }

  private TestCase findExisting(String fullyQualifiedName) {
    return repository
        .getByNameOrNull(
            null,
            fullyQualifiedName,
            repository.getFields(TEST_DEFINITION_FIELD),
            Include.NON_DELETED,
            false)
        .orElse(null);
  }

  private static boolean isOwnedBySomeoneElse(TestCase existing, Request request) {
    return existing != null
        && !request.ownedTestCaseIds().contains(existing.getId())
        && !existing.getName().startsWith(ODCSTestCaseNames.GENERATED_PREFIX);
  }

  private static Optional<EntityReference> linkIfSameDefinition(
      TestCase existing, TestCase wanted) {
    String existingDefinition = existing.getTestDefinition().getFullyQualifiedName();
    String wantedDefinition = wanted.getTestDefinition().getFullyQualifiedName();
    return existingDefinition.equalsIgnoreCase(wantedDefinition)
        ? Optional.of(existing.getEntityReference())
        : Optional.empty();
  }

  private static UnsupportedOutcome nameTakenOutcome(TestCaseOutcome outcome) {
    return new UnsupportedOutcome(
        outcome.rule(),
        String.format(
            "A different test case named '%s' already exists there and was left unchanged.",
            outcome.testCase().getName()));
  }

  private static String rejectedReason(RuntimeException e) {
    return "OpenMetadata rejected the test case: " + e.getMessage();
  }
}
