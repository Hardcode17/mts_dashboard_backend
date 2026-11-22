// Run against the stopped demo ITSM's volume, using Node from the deployed frontend image.
import {copyFileSync, readFileSync, renameSync, writeFileSync} from 'node:fs'

const [action, statePath, fixturePath] = process.argv.slice(2)
if (!['check', 'reset', 'send'].includes(action)) throw Error('Unknown operation')
const read = path => JSON.parse(readFileSync(path, 'utf8'))
const delivery = 'INC943741270571', critical = 'INC943741270574', sysId = '19437412705704'
const ids = new Set([delivery, 'INC000021940126', ...Array.from({length:99}, (_, i) => 'INC' + (980000000001 + i))])
const baseline = new Map(read(fixturePath).filter(row => ids.has(row.id)).map(row => [row.id, row]))
const state = read(statePath)
if (baseline.size !== 101 || [...baseline.values()].some(row => row.priorityId !== 2)) throw Error('Unexpected mentor fixture')
if (!state.deliveredVersion || (action !== 'check' && (state.pending || state.rejection))) throw Error('ITSM still has an unconfirmed batch; retry when delivery is healthy')
if (new Set(state.rows.filter(row => ids.has(row.id)).map(row => row.id)).size !== 101) throw Error('Deployed mentor records are missing')
const reserved = state.rows.filter(row => row.id === critical || row.sysId === sysId)
if (reserved.length > 1 || reserved.some(row => row.id !== critical || row.sysId !== sysId || row.mentorScenario !== 'pi-critical')) {
  throw Error('Reserved identity already belongs to another incident')
}
if (action === 'check') {
  console.log('Ready: existing ITSM delivery and 100 mentor records')
  process.exit(0)
}
if (action === 'reset') {
  state.rows = state.rows.filter(row => row.id !== critical).map(row => baseline.get(row.id) ?? row)
  state.manualStates = (state.manualStates ?? []).filter(id => !ids.has(id) && id !== critical)
} else if (!reserved.length) {
  const row = state.rows.find(r => r.id === delivery), now = new Date()
  if (row.priorityId !== 2 || row.assignedGroupName !== baseline.get(delivery).assignedGroupName ||
      !['Assigned','WorkInProgress'].includes(row.stateEnum)) throw Error('Reset the existing delivery before sending the critical incident')
  state.rows.push({...baseline.get(delivery), id:critical, sysId, mentorScenario:'pi-critical',
    productName:'Демо · МТС ID', service:'Авторизация', scenarioName:'Вход',
    header:'Менторская сессия · массовая ошибка входа', description:'Пользователи не могут войти через МТС ID.',
    priorityId:4, priority:'Наивысший', submitDate:now.toISOString(), lastAssignmentToTeamDateTime:now.toISOString(),
    deadline:new Date(now.getTime()+30*60000).toISOString()})
} else if (!['Assigned','WorkInProgress'].includes(reserved[0].stateEnum) || reserved[0].priorityId !== 4) {
  throw Error('The previous incident was edited. Reset before the next demonstration')
} else {
  console.log('The demo critical incident already exists')
  process.exit(0)
}
copyFileSync(statePath, statePath + '.before-mentor')
writeFileSync(statePath + '.mentor-next', JSON.stringify(state), {mode:0o600})
renameSync(statePath + '.mentor-next', statePath)
