/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.core.domain;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;

/** The exact user-activated rule revision that authorized one action. */
public record RuleAuthorization(String ruleId, String ruleName, long ruleVersion)
{
    public RuleAuthorization
    {
        checkText(ruleId, "ruleId");
        checkText(ruleName, "ruleName");
        checkArgument(ruleVersion >= 0, "ruleVersion is negative");
    }
}
