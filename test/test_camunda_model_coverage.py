#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CAMUNDA = ROOT / 'src/main/resources/camunda'

BPMN = {'bpmn': 'http://www.omg.org/spec/BPMN/20100524/MODEL'}
DMN = {'dmn': 'https://www.omg.org/spec/DMN/20191111/MODEL/'}


def read(path: Path) -> str:
    return path.read_text(encoding='utf-8')


def parse(path: Path) -> ET.Element:
    return ET.parse(path).getroot()


def ensure(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def has_bpmn(path: Path, xpath: str) -> bool:
    return parse(path).find(xpath, BPMN) is not None


def has_explicit_compensation_throw(path: Path) -> bool:
    root = parse(path)
    for throw_event in root.findall('.//bpmn:intermediateThrowEvent', BPMN):
        for definition in throw_event.findall('bpmn:compensateEventDefinition', BPMN):
            if definition.get('waitForCompletion') == 'true':
                return True
    return False


def form_validation_loop_exists(path: Path, validation_node_id: str, user_task_id: str) -> bool:
    root = parse(path)
    boundaries = [
        item for item in root.findall('.//bpmn:boundaryEvent', BPMN)
        if item.get('attachedToRef') == validation_node_id
        and item.find('bpmn:errorEventDefinition', BPMN) is not None
    ]
    for boundary in boundaries:
        boundary_id = boundary.get('id')
        for flow in root.findall('.//bpmn:sequenceFlow', BPMN):
            if flow.get('sourceRef') == boundary_id and flow.get('targetRef') == user_task_id:
                return True
    return False


def main() -> int:
    for path in sorted(CAMUNDA.glob('*.bpmn')):
        root = parse(path)
        ensure(root.find('.//{http://www.omg.org/spec/BPMN/20100524/DI}BPMNDiagram') is not None,
               f'{path.name} has no BPMNDiagram')

        sequence_ids = {
            item.get('id')
            for item in root.findall('.//bpmn:sequenceFlow', BPMN)
            if item.get('id')
        }
        edge_ids = {
            item.get('bpmnElement')
            for item in root.findall('.//{http://www.omg.org/spec/BPMN/20100524/DI}BPMNEdge')
            if item.get('bpmnElement')
        }
        missing_edges = sorted(sequence_ids - edge_ids)
        ensure(not missing_edges, f'{path.name} sequence flows without DI: {missing_edges}')

    for name in [
        'hh-operation-permissions.dmn',
        'hh-auto-screening.dmn',
        'hh-status-transitions.dmn',
        'hh-notification-templates.dmn',
    ]:
        root = parse(CAMUNDA / name)
        decisions = root.findall('.//dmn:decision', DMN)
        ensure(decisions, f'{name} has no decision')
        ensure(all(decision.get('{http://camunda.org/schema/1.0/dmn}historyTimeToLive') == '30'
                   for decision in decisions),
               f'{name} decisions must have Camunda historyTimeToLive=30')

    app = CAMUNDA / 'hh-application-process.bpmn'
    vacancy = CAMUNDA / 'hh-vacancy-process.bpmn'
    notification = CAMUNDA / 'hh-notification-process.bpmn'
    scheduler = CAMUNDA / 'hh-timeout-scheduler.bpmn'

    ensure('camunda:decisionRef="hhAutoScreening"' in read(app), 'application BPMN must call hhAutoScreening')
    ensure('camunda:decisionRef="hhStatusTransitions"' in read(app), 'application BPMN must call hhStatusTransitions')
    ensure('camunda:decisionRef="hhStatusTransitions"' in read(vacancy), 'vacancy BPMN must call hhStatusTransitions')
    ensure('camunda:decisionRef="hhNotificationTemplates"' in read(notification),
           'notification subprocess must call hhNotificationTemplates')

    for message in ['MSG_VACANCY_CLOSED', 'MSG_INTERVIEW_CANCELLED', 'MSG_ADMIN_RESET_DONE', 'MSG_INVITATION_EXPIRED']:
        ensure(message in read(app), f'application BPMN must expose message boundary event {message}')

    for path in [app, vacancy, scheduler, CAMUNDA / 'hh-admin-interview-reset.bpmn',
                 CAMUNDA / 'hh-recruiter-interview-cancel.bpmn']:
        ensure(has_explicit_compensation_throw(path),
               f'{path.name} must explicitly throw compensation in rollback branch')

    validation_loops = {
        'hh-application-process.bpmn': [
            ('ValidateApplyToVacancyForm', 'ApplyToVacancyTask'),
            ('ValidateRecruiterDecisionForm', 'RecruiterDecisionTask'),
            ('ValidateInvitationForm', 'WriteInvitationTask'),
            ('ValidateCandidateResponseForm', 'CandidateInvitationResponseTask'),
        ],
        'hh-vacancy-process.bpmn': [
            ('ValidateCreateVacancyForm', 'CreateVacancyTask'),
            ('ValidateCloseVacancyForm', 'ManageVacancyTask'),
        ],
        'hh-admin-interview-reset.bpmn': [
            ('ValidateAdminResetForm', 'AdminResetApprovalTask'),
        ],
        'hh-recruiter-interview-cancel.bpmn': [
            ('ValidateRecruiterCancelInterview', 'CancelInterviewTask'),
        ],
        'hh-vacancy-status-update.bpmn': [
            ('ValidateVacancyStatusUpdate', 'UpdateVacancyStatusTask'),
        ],
        'hh-ui-candidate-application-view.bpmn': [
            ('LoadCandidateApplicationView', 'EnterCandidateApplicationId'),
        ],
        'hh-ui-recruiter-application-view.bpmn': [
            ('LoadRecruiterApplicationView', 'EnterRecruiterApplicationId'),
        ],
        'hh-ui-recruiter-schedule.bpmn': [
            ('LoadRecruiterSchedule', 'EnterScheduleWeek'),
        ],
        'hh-ui-admin-timeout-review.bpmn': [
            ('RunTimeoutReview', 'ConfirmTimeoutReview'),
        ],
    }
    for file_name, loops in validation_loops.items():
        path = CAMUNDA / file_name
        for validation_node_id, user_task_id in loops:
            ensure(form_validation_loop_exists(path, validation_node_id, user_task_id),
                   f'{file_name} must route FORM_VALIDATION_FAILED from {validation_node_id} back to {user_task_id}')

    adapter = read(ROOT / 'src/main/java/ru/itmo/hhprocess/camunda/CamundaProcessAdapterService.java')
    run_timeout_review = adapter.split('public Map<String, Object> runTimeoutReview', 1)[1].split('\\n    }', 1)[0]
    ensure('runCloseExpired' not in run_timeout_review,
           'Camunda UI timeout review must not call TimeoutService.runCloseExpired fallback')

    docs = read(ROOT / 'CAMUNDA_README.md')
    ensure('Postman' not in docs, 'Defense README must not rely on Postman')
    ensure('Swagger UI' not in docs, 'Defense README must not rely on Swagger UI')

    print('Camunda model coverage checks passed')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except AssertionError as exc:
        print(exc)
        sys.exit(1)
