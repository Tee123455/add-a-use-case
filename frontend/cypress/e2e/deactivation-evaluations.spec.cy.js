// T8: real invitation/registration, roster, refusal and recovery. Disposable evaluator for independent reruns.
const api = Cypress.env('apiUrl') || 'http://localhost/api/v1'
const mailpit = Cypress.env('mailpitUrl') || 'http://localhost:8025'
let admin, evaluator, evaluatorId
const auth = (session) => ({ Authorization: `Bearer ${session.token}` })
const login = (email, password = '123456') => cy.request({ method: 'POST', url: `${api}/users/login`, auth: { username: email, password } }).its('body.data')
function restoreWoody() {
  return cy.request({ url: `${api}/students/7`, headers: auth(admin) }).then(({ body }) => {
    if (!body.data.enabled) cy.request({ method: 'PUT', url: `${api}/students/7?expectedEnabled=false`, headers: auth(admin), body: { ...body.data, enabled: true } })
  })
}
function visitEvaluations() {
  cy.visit('/evaluations/submit-evaluations', {
    onBeforeLoad(win) {
      win.localStorage.clear()
      win.localStorage.setItem('token', JSON.stringify({ token: evaluator.token }))
      win.localStorage.setItem('userInfo', JSON.stringify({ userInfo: evaluator.userInfo }))
    }
  })
}

describe('Deactivation and new peer evaluations', () => {
  beforeEach(() => {
    evaluatorId = undefined
    login('b.wei@abc.edu').then((session) => { admin = session })
    cy.then(restoreWoody)
    const email = `uc-stu-${Date.now()}@abc.edu`
    cy.then(() => cy.request({ method: 'POST', url: `${api}/sections/2/students/email-invitations?courseId=1`, headers: auth(admin), body: [email] }))
    cy.request(`${mailpit}/api/v1/messages`).then(({ body }) => {
      const message = body.messages.find((message) => message.To.some((to) => to.Address === email))
      expect(message, 'invitation delivered to Mailpit').to.exist
      return cy.request(`${mailpit}/api/v1/message/${message.ID}`)
    }).then(({ body }) => {
      const token = body.HTML.match(/[?&]token=([^&"\s]+)/)[1]
      return cy.request({ method: 'POST', url: `${api}/students?courseId=1&sectionId=2&role=student&registrationToken=${token}`, body: {
        username: email, email, password: 'Abc123456', firstName: 'Cypress', lastName: 'Evaluator'
      } })
    }).then(({ body }) => {
      evaluatorId = body.data.id
      return cy.request({ method: 'PUT', url: `${api}/teams/2/students/${evaluatorId}`, headers: auth(admin) })
    }).then(() => login(email, 'Abc123456')).then((session) => { evaluator = session })
  })
  afterEach(() => {
    cy.visit('/login', { onBeforeLoad(win) { win.localStorage.clear() } })
    cy.then(restoreWoody)
    cy.then(() => {
      if (evaluatorId) {
        cy.request({ method: 'PUT', url: `${api}/students/${evaluatorId}/deactivate`, headers: auth(admin) })
        cy.request({ method: 'DELETE', url: `${api}/teams/2/students/${evaluatorId}`, headers: auth(admin) })
      }
    })
  })

  it('filters inactive teammates, rejects a stale roster, refreshes drafts and allows remaining submissions (T8)', () => {
    cy.then(() => cy.request({ method: 'PUT', url: `${api}/students/7/deactivate`, headers: auth(admin) }))
    cy.clock(new Date('2023-08-20T12:00:00-05:00').getTime(), ['Date'])
    visitEvaluations()
    cy.contains('.el-table__row', 'Amanda Wagner').should('be.visible')
    cy.contains('.el-table__row', 'Woody Allen').should('not.exist')
    cy.then(restoreWoody)
    visitEvaluations()
    cy.contains('.el-table__row', 'Woody Allen').should('be.visible')
    cy.contains('.el-table__row', 'Amanda Wagner').find('textarea').first().type('Keep this draft')
    cy.then(() => cy.request({ method: 'PUT', url: `${api}/students/7/deactivate`, headers: auth(admin) }))
    cy.get('[data-cy=evaluations-submit]').click()
    cy.get('[data-cy=evaluation-inactive-error]').should('contain', 'inactive').and('contain', 'Refresh teammates')
    cy.get('[data-cy=evaluation-refresh]').click()
    cy.contains('.el-table__row', 'Woody Allen').should('not.exist')
    cy.contains('.el-table__row', 'Amanda Wagner').find('textarea').first().should('have.value', 'Keep this draft')
    cy.get('[data-cy=evaluation-inactive-error]').should('not.exist')
    cy.get('.el-message--success').should('not.exist') // Refresh never submits a draft.
    cy.get('[data-cy=evaluations-submit]').click()
    cy.contains('.el-message--success', 'Peer evaluations submitted successfully').should('be.visible')
    cy.then(() => cy.request({ url: `${api}/evaluations/evaluators/${evaluatorId}/week/2023-W32`, headers: auth(evaluator) })).then(({ body }) => {
      expect(body.data.map((row) => row.evaluateeId)).not.to.include(7)
      expect(body.data.map((row) => row.evaluateeId)).to.include(evaluatorId)
      expect(body.data.find((row) => row.evaluateeId === 8).publicComment).to.eq('Keep this draft')
    })
  })
})
