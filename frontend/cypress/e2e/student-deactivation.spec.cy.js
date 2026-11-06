// UC-STU-deactivate-student. Run against the seeded dev stack; no successful API responses are fabricated.
const api = Cypress.env('apiUrl') || 'http://localhost/api/v1'
let admin
const headers = () => ({ Authorization: `Bearer ${admin.token}` })
const authenticate = (email) => cy.request({ method: 'POST', url: `${api}/users/login`, auth: { username: email, password: '123456' } }).its('body.data')

function visitAs(email, path) {
  authenticate(email).then((session) => {
    cy.visit(path, {
      onBeforeLoad(win) {
        win.localStorage.clear()
        win.localStorage.setItem('token', JSON.stringify({ token: session.token }))
        win.localStorage.setItem('userInfo', JSON.stringify({ userInfo: session.userInfo }))
        win.localStorage.setItem('settings', JSON.stringify({ defaultSectionId: 2, defaultCourseId: 1 }))
      }
    })
  })
}
function restoreJohn() {
  cy.request({ url: `${api}/students/4`, headers: headers() }).then(({ body }) => {
    if (!body.data.enabled) {
      // Retained existing profile behavior is test cleanup, not a new reactivation operation.
      cy.request({ method: 'PUT', url: `${api}/students/4?expectedEnabled=false`, headers: headers(), body: { ...body.data, enabled: true } })
    }
  })
}
function deactivateJohn() {
  return cy.request({ method: 'PUT', url: `${api}/students/4/deactivate`, headers: headers() })
}
function johnRow() { return cy.contains('.el-table__row', 'j.smith@abc.edu') }
function openWarning() {
  johnRow().find('[data-cy=student-details-open]').click()
  cy.get('[data-cy=student-details]').should('contain', 'John Smith').and('contain', 'j.smith@abc.edu')
  cy.get('[data-cy=deactivate-open]').click()
  cy.get('[data-cy=deactivate-dialog]').should('be.visible')
}

describe('Student deactivation', () => {
  beforeEach(() => {
    authenticate('b.wei@abc.edu').then((data) => { admin = data })
    cy.then(restoreJohn)
    visitAs('b.gates@abc.edu', '/students')
  })
  afterEach(() => { restoreJohn() })

  it('shows the specified warning and persists only after confirmation (T1)', () => {
    openWarning()
    cy.get('[data-cy=deactivate-target]').should('contain', 'John Smith').and('contain', 'j.smith@abc.edu')
    cy.get('[data-cy=deactivate-consequences]').should('contain', 'existing login').and('contain', 'Team membership')
      .and('contain', 'historical work remain').and('contain', 'by or about').and('contain', 'historical weekly results remain').and('contain', 'reminders stop')
    cy.get('[data-cy=deactivate-dialog]').should('contain', 'permanent deletion').and('contain', 'reactivation')
    cy.screenshot('student-deactivation-confirmation')
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.contains('.el-message--success', 'Student deactivated successfully').should('be.visible')
    johnRow().should('contain', 'Disabled')
    cy.request({ url: `${api}/students/4`, headers: headers() }).its('body.data.enabled').should('eq', false)
  })

  for (const action of ['Cancel', 'close', 'Escape', 'backdrop']) {
    it(`sends no request when dismissed using ${action} (T3 / 4a)`, () => {
      cy.intercept('PUT', '**/students/4/deactivate').as('deactivate')
      openWarning()
      if (action === 'Cancel') cy.get('[data-cy=deactivate-cancel]').click()
      else if (action === 'close') cy.get('[data-cy=deactivate-dialog] .el-dialog__headerbtn').click()
      else if (action === 'Escape') cy.get('body').type('{esc}')
      else cy.get('[data-cy=deactivate-dialog]').closest('.el-overlay-dialog').click('topLeft')
      cy.get('[data-cy=deactivate-dialog]').should('not.be.visible')
      cy.get('@deactivate.all').should('have.length', 0)
      cy.request({ url: `${api}/students/4`, headers: headers() }).its('body.data.enabled').should('eq', true)
    })
  }

  it('reports an already-inactive target when another request wins (T4 / 6a)', () => {
    openWarning()
    deactivateJohn()
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.contains('.el-message--success', 'Student is already deactivated').should('be.visible')
    johnRow().should('contain', 'Disabled')
  })

  for (const failure of ['server', 'network']) {
    it(`shows no success or false status after ${failure} failure (T11)`, () => {
      cy.intercept('PUT', '**/students/4/deactivate', failure === 'network'
        ? { forceNetworkError: true }
        : { statusCode: 500, body: { flag: false, code: 500, message: 'Controlled failure' } }).as('failed')
      openWarning()
      cy.get('[data-cy=deactivate-confirm]').click()
      cy.wait('@failed')
      cy.get('[data-cy=deactivate-confirm]').should('be.enabled')
      cy.get('.el-message--success').should('not.exist')
      johnRow().should('contain', 'Enabled')
      // A lost response does not prove the request never committed. T11's oracle is no false UI success.
      if (failure === 'server') cy.request({ url: `${api}/students/4`, headers: headers() }).its('body.data.enabled').should('eq', true)
    })
  }

  it('prevents duplicate confirmation while the real response is pending (T11)', () => {
    cy.intercept('PUT', '**/students/4/deactivate', (req) => req.continue((res) => res.setDelay(1000))).as('deactivate')
    openWarning()
    cy.get('[data-cy=deactivate-confirm]').click().should('be.disabled').click({ force: true })
    cy.wait('@deactivate')
    cy.get('@deactivate.all').should('have.length', 1)
    johnRow().should('contain', 'Disabled')
  })

  it('uses the shared 401 logout behavior (T11)', () => {
    cy.intercept('PUT', '**/students/4/deactivate', { statusCode: 401, body: { flag: false, code: 401, message: 'Unauthorized' } }).as('unauthorized')
    openWarning()
    cy.get('[data-cy=deactivate-confirm]').click()
    cy.wait('@unauthorized')
    cy.location('pathname').should('eq', '/login')
    cy.window().then((win) => {
      expect(JSON.parse(win.localStorage.getItem('token')).token).to.eq('')
      expect(JSON.parse(win.localStorage.getItem('userInfo')).userInfo).to.eq(null)
    })
    cy.get('.el-message--success').should('not.exist')
  })

  it('disables generic deactivation and keeps a stale profile open on 409 (T13/T14)', () => {
    cy.intercept('PUT', '**/students/4?*').as('profileSave')
    johnRow().find('[data-cy=student-edit-open]').click()
    cy.get('[data-cy=student-edit-status]').should('have.class', 'is-disabled')
    cy.get('[data-cy=dedicated-deactivation-hint]').should('be.visible')
    cy.get('[aria-label="Edit a student"]').contains('.el-form-item', 'First Name:').find('input').clear().type('Stale name')
    deactivateJohn()
    cy.get('[data-cy=student-edit-save]').click()
    cy.wait('@profileSave').then(({ request, response }) => {
      expect(request.query.expectedEnabled).to.eq('true')
      expect(response.statusCode).to.eq(409)
    })
    cy.get('[data-cy=profile-conflict]').should('be.visible')
    cy.request({ url: `${api}/students/4`, headers: headers() }).then(({ body }) => {
      expect(body.data.enabled).to.eq(false)
      expect(body.data.firstName).to.eq('John')
    })
    cy.get('[data-cy=profile-refresh]').click()
    cy.get('[data-cy=student-edit-status]').should('not.have.class', 'is-disabled')
    cy.get('[aria-label="Edit a student"]').contains('.el-form-item', 'First Name:').find('input').should('have.value', 'John')
  })

  it('keeps the inactive snapshot separate from intentional reactivation (T14)', () => {
    deactivateJohn()
    cy.intercept('PUT', '**/students/4?*').as('reactivateProfile')
    johnRow().find('[data-cy=student-edit-open]').click()
    cy.get('[data-cy=student-edit-status]').should('not.have.class', 'is-disabled').click()
    cy.get('[data-cy=student-edit-save]').click()
    cy.wait('@reactivateProfile').then(({ request, response }) => {
      expect(request.query.expectedEnabled).to.eq('false')
      expect(request.body.enabled).to.eq(true)
      expect(response.statusCode).to.eq(200)
      expect(response.body.data.enabled).to.eq(true)
    })
    cy.request({ url: `${api}/students/4`, headers: headers() }).its('body.data.enabled').should('eq', true)
  })

  it('sends the loaded status from the current-user profile and preserves the form on conflict (T14)', () => {
    visitAs('j.smith@abc.edu', '/user/profile')
    cy.intercept('PUT', '**/students/4?*', { statusCode: 409, body: { flag: false, code: 409, message: 'Status changed; refresh.' } }).as('conflict')
    cy.get('[data-cy=user-profile-save]').click()
    cy.wait('@conflict').its('request.query.expectedEnabled').should('eq', 'true')
    cy.get('[data-cy=profile-conflict]').should('be.visible')
    cy.location('pathname').should('eq', '/user/profile')
  })
})
