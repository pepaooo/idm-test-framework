/** Letters, digits, `_`, then also spaces, `.` and `-`: a plain YAML scalar as written. */
const PLAIN = /^[\p{L}\p{N}_][\p{L}\p{N}_ .-]*$/u;
/** What YAML 1.1 or 1.2 reads as null or a boolean, in any letter case. */
const RESERVED = /^(null|~|true|false|yes|no|on|off|y|n)$/i;
/** Starts with a digit: may read as a number (1.50, 0x10, 1_000, 1e3) or a date (2026-10-24). */
const NUMBER_LIKE = /^\p{N}/u;

/** Whether `name` written plain after `name: ` reads back as that same text. */
const readsAsText = (name: string) => PLAIN.test(name) && !RESERVED.test(name) && !NUMBER_LIKE.test(name);

/**
 * What a new scenario starts from (New scenario in the file tree): a name and description, a
 * random-user fixture, one `set` step (the validator refuses a scenario with no steps, and `set`
 * touches no system) and a commented LDAP create/verify/cleanup to start from. It validates with
 * zero problems: the same text is FilesControllerTest.TEMPLATE, checked against the real validator.
 * `name` is the file name without its extension; a name YAML would not read back as that text (it
 * starts with `#`, `[`, `'`…, is null/true/yes/on…, or starts with a digit) is written in double quotes.
 */
export function scenarioTemplate(name: string): string {
  return `name: ${readsAsText(name) ? name : JSON.stringify(name)}
description: Describe what this scenario checks.

fixtures:
  user:
    generate: random-user

steps:
  - set:
      userDn: "cn=\${user.userId},ou=users,o=data"
#  - ldap.create:
#      dn: "\${userDn}"
#      as: createdDn
#      attributes:
#        cn: "\${user.userId}"
#        sn: "\${user.lastName}"
#  - ldap.verify:
#      dn: "\${createdDn}"
#      exists: true

#cleanup:
#  - ldap.delete:
#      dn: "\${createdDn}"
`;
}
