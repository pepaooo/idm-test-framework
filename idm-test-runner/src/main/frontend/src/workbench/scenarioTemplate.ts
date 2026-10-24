/** Letters, digits, `_`, then also spaces, `.` and `-`: a plain YAML scalar as written. */
const PLAIN = /^[\p{L}\p{N}_][\p{L}\p{N}_ .-]*$/u;

/**
 * What a new scenario starts from (New scenario in the file tree): a name and description, a
 * random-user fixture, one `set` step (the validator refuses a scenario with no steps, and `set`
 * touches no system) and a commented LDAP create/verify/cleanup to start from. It validates with
 * zero problems: the same text is FilesControllerTest.TEMPLATE, checked against the real validator.
 * `name` is the file name without its extension; a name YAML would not read as plain text (it starts
 * with `#`, `[`, `'`…) is written in double quotes.
 */
export function scenarioTemplate(name: string): string {
  return `name: ${PLAIN.test(name) ? name : JSON.stringify(name)}
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
