/*
 * Copyright 2016 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.clouddriver.deploy


import com.netflix.spectator.api.NoopRegistry
import com.netflix.spinnaker.clouddriver.security.AccountDefinitionAuthorizer
import com.netflix.spinnaker.clouddriver.security.AccountDefinitionSecretManager
import com.netflix.spinnaker.clouddriver.security.config.SecurityConfig
import com.netflix.spinnaker.clouddriver.security.resources.AccountNameable
import com.netflix.spinnaker.clouddriver.security.resources.ApplicationNameable
import com.netflix.spinnaker.clouddriver.security.resources.ResourcesNameable
import com.netflix.spinnaker.fiat.model.resources.ResourceType
import com.netflix.spinnaker.fiat.shared.FiatPermissionEvaluator
import com.netflix.spinnaker.kork.secrets.SecretManager
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Unroll

class DescriptionAuthorizerServiceSpec extends Specification {
  def registry = new NoopRegistry()
  def evaluator = Mock(FiatPermissionEvaluator)
  def secretManager = Mock(SecretManager)
  def opsSecurityConfigProps

  @Subject
  DescriptionAuthorizerService service

  def setup() {
    opsSecurityConfigProps = new SecurityConfig.OperationsSecurityConfigurationProperties()
    service = new DescriptionAuthorizerService(registry, Optional.of(evaluator), opsSecurityConfigProps, new AccountDefinitionSecretManager(secretManager, new AccountDefinitionAuthorizer(evaluator)))
  }

  def "should authorize passed description"() {
    given:
    def auth = new TestingAuthenticationToken(null, null)

    def ctx = SecurityContextHolder.createEmptyContext()
    ctx.setAuthentication(auth)
    SecurityContextHolder.setContext(ctx)

    def description = new TestDescription(
      "testAccount", ["testApplication", null], ["testResource1", "testResource2", null]
    )

    def errors = new DescriptionValidationErrors(description)

    when:
    service.authorize(description, errors)

    then:
    4 * evaluator.hasPermission(*_) >> false
    1 * evaluator.storeWholePermission()
    errors.allErrors.size() == 4
  }

  @Unroll
  def "should skip authentication for image tagging description if allowUnauthenticatedImageTaggingInAccounts contains the account"() {
    given:
    def description = new TestImageTaggingDescription("testAccount")

    def errors = new DescriptionValidationErrors(description)

    opsSecurityConfigProps.allowUnauthenticatedImageTaggingInAccounts = allowUnauthenticatedImageTaggingInAccounts

    when:
    service.authorize(description, errors)

    then:
    expectedNumberOfInvocations * evaluator.hasPermission(*_) >> false
    0 * evaluator.storeWholePermission()
    errors.allErrors.size() == expectedNumberOfErrors

    where:
    allowUnauthenticatedImageTaggingInAccounts || expectedNumberOfErrors || expectedNumberOfInvocations
    ["testAccount"]                            || 0                      || 0
    ["anotherAccount"]                         || 1                      || 1
    []                                         || 1                      || 1
  }

  @Unroll
  def "should only authz specified resource type"() {
    given:
    def auth = new TestingAuthenticationToken(null, null)

    def ctx = SecurityContextHolder.createEmptyContext()
    ctx.setAuthentication(auth)
    SecurityContextHolder.setContext(ctx)

    def description = new TestDescription(
      "testAccount", ["testApplication", null], ["testResource1", "testResource2", null]
    )

    def errors = new DescriptionValidationErrors(description)

    when:
    service.authorize(description, errors, List.of(resourceType))

    then:
    expectedNumberOfAuthChecks * evaluator.hasPermission(*_) >> false
    errors.allErrors.size() == expectedNumberOfErrors

    where:
    resourceType              || expectedNumberOfAuthChecks | expectedNumberOfErrors
    ResourceType.APPLICATION  || 3                          | 3
    ResourceType.ACCOUNT      || 1                          | 1
  }

  class TestDescription implements AccountNameable, ApplicationNameable, ResourcesNameable {
    String account
    Collection<String> applications
    List<String> names

    TestDescription(String account, Collection<String> applications, List<String> names) {
      this.account = account
      this.applications = applications
      this.names = names
    }
  }

  class TestImageTaggingDescription implements AccountNameable {
    String account

    TestImageTaggingDescription(String account) {
      this.account = account
    }

    @Override
    boolean requiresApplicationRestriction() {
      return false
    }

    @Override
    boolean requiresAuthorization(SecurityConfig.OperationsSecurityConfigurationProperties opsSecurityConfigProps) {
      return !opsSecurityConfigProps.allowUnauthenticatedImageTaggingInAccounts.contains(account)
    }
  }
}
