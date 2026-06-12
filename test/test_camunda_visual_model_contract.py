#!/usr/bin/env python3
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict, deque
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CAMUNDA = ROOT / 'src/main/resources/camunda'

BPMN_URI = 'http://www.omg.org/spec/BPMN/20100524/MODEL'
BPMNDI_URI = 'http://www.omg.org/spec/BPMN/20100524/DI'
DC_URI = 'http://www.omg.org/spec/DD/20100524/DC'
DI_URI = 'http://www.omg.org/spec/DD/20100524/DI'
CAMUNDA_URI = 'http://camunda.org/schema/1.0/bpmn'

BPMN = {'bpmn': BPMN_URI, 'bpmndi': BPMNDI_URI}
FLOW_NODE_TAGS = {
    'startEvent',
    'endEvent',
    'intermediateCatchEvent',
    'intermediateThrowEvent',
    'boundaryEvent',
    'serviceTask',
    'userTask',
    'businessRuleTask',
    'scriptTask',
    'manualTask',
    'exclusiveGateway',
    'parallelGateway',
    'eventBasedGateway',
    'transaction',
    'subProcess',
    'callActivity',
}


def local(tag: str) -> str:
    return tag.split('}', 1)[-1] if tag.startswith('{') else tag


def parse(path: Path) -> ET.Element:
    return ET.parse(path).getroot()


def ensure(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def flow_node_ids(root: ET.Element) -> set[str]:
    result = set()
    for item in root.iter():
        if local(item.tag) in FLOW_NODE_TAGS and item.get('id'):
            result.add(item.get('id'))
    return result


def distance_to_bounds(point: tuple[float, float], bounds: ET.Element) -> float:
    x, y = point
    bx = float(bounds.get('x', '0'))
    by = float(bounds.get('y', '0'))
    bw = float(bounds.get('width', '0'))
    bh = float(bounds.get('height', '0'))
    closest_x = min(max(x, bx), bx + bw)
    closest_y = min(max(y, by), by + bh)
    return ((x - closest_x) ** 2 + (y - closest_y) ** 2) ** 0.5


def process_scope(item: ET.Element, parents: dict[ET.Element, ET.Element]) -> str | None:
    parent = parents.get(item)
    while parent is not None and local(parent.tag) not in {'process', 'subProcess', 'transaction'}:
        parent = parents.get(parent)
    return parent.get('id') if parent is not None else None


def main() -> int:
    all_process_ids = set()
    for path in sorted(CAMUNDA.glob('*.bpmn')):
        root = parse(path)
        all_process_ids.update(
            process.get('id')
            for process in root.findall('bpmn:process', BPMN)
            if process.get('id')
        )

    for path in sorted(CAMUNDA.glob('*.bpmn')):
        root = parse(path)
        parents = {child: parent for parent in root.iter() for child in parent}
        processes = root.findall('bpmn:process', BPMN)
        ensure(processes, f'{path.name} has no BPMN process')

        for process in processes:
            process_id = process.get('id')
            ensure(process_id, f'{path.name} has process without id')

            participants = [
                item for item in root.findall('.//bpmn:participant', BPMN)
                if item.get('processRef') == process_id
            ]
            ensure(participants, f'{path.name} has no pool participant for {process_id}')
            ensure(all((item.get('name') or '').strip() for item in participants),
                   f'{path.name} has unnamed pool participant')

            lanes = process.findall('.//bpmn:lane', BPMN)
            ensure(lanes, f'{path.name} has no lanes')
            ensure(all((lane.get('name') or '').strip() for lane in lanes),
                   f'{path.name} has unnamed lane')
            ensure(any(lane.findall('bpmn:flowNodeRef', BPMN) for lane in lanes),
                   f'{path.name} lanes do not reference flow nodes')

        starts = root.findall('.//bpmn:startEvent', BPMN)
        ends = root.findall('.//bpmn:endEvent', BPMN)
        ensure(starts, f'{path.name} has no start event')
        ensure(ends, f'{path.name} has no end event')
        unnamed_events = [
            item.get('id') for item in starts + ends
            if not (item.get('name') or '').strip()
        ]
        ensure(not unnamed_events, f'{path.name} has unnamed start/end events: {unnamed_events}')

        diagram = root.find('.//bpmndi:BPMNDiagram', BPMN)
        plane = root.find('.//bpmndi:BPMNPlane', BPMN)
        ensure(diagram is not None and plane is not None,
               f'{path.name} has no BPMN DI diagram/plane')
        plane_element = plane.get('bpmnElement')
        collaborations = {item.get('id') for item in root.findall('bpmn:collaboration', BPMN)}
        ensure(plane_element in collaborations,
               f'{path.name} BPMNPlane must point to collaboration/pool, got {plane_element}')

        shapes = {
            item.get('bpmnElement')
            for item in root.findall('.//bpmndi:BPMNShape', BPMN)
            if item.get('bpmnElement')
        }
        edges = {
            item.get('bpmnElement')
            for item in root.findall('.//bpmndi:BPMNEdge', BPMN)
            if item.get('bpmnElement')
        }
        missing_shapes = sorted(flow_node_ids(root) - shapes)
        ensure(not missing_shapes, f'{path.name} flow nodes without BPMNShape: {missing_shapes}')

        participant_ids = {
            item.get('id') for item in root.findall('.//bpmn:participant', BPMN)
            if item.get('id')
        }
        missing_pool_shapes = sorted(participant_ids - shapes)
        ensure(not missing_pool_shapes, f'{path.name} pool participants without BPMNShape: {missing_pool_shapes}')

        lane_ids = {
            item.get('id') for item in root.findall('.//bpmn:lane', BPMN)
            if item.get('id')
        }
        missing_lane_shapes = sorted(lane_ids - shapes)
        ensure(not missing_lane_shapes, f'{path.name} lanes without BPMNShape: {missing_lane_shapes}')

        bounds_by_element = {}
        for item in root.findall('.//bpmndi:BPMNShape', BPMN):
            element = item.get('bpmnElement')
            bounds = item.find(f'{{{DC_URI}}}Bounds')
            if element and bounds is not None:
                bounds_by_element[element] = bounds

        for participant_id in participant_ids:
            participant_bounds = bounds_by_element.get(participant_id)
            if participant_bounds is None:
                continue
            pool_x = float(participant_bounds.get('x', '0'))
            for lane_id in lane_ids:
                lane_bounds = bounds_by_element.get(lane_id)
                if lane_bounds is None:
                    continue
                lane_x = float(lane_bounds.get('x', '0'))
                ensure(lane_x - pool_x >= 100,
                       f'{path.name} pool/lane label area is too narrow: {participant_id} -> {lane_id}')

        flow_ids = {
            item.get('id') for item in root.findall('.//bpmn:sequenceFlow', BPMN)
            if item.get('id')
        }
        missing_edges = sorted(flow_ids - edges)
        ensure(not missing_edges, f'{path.name} sequence flows without BPMNEdge: {missing_edges}')

        connectors = {
            item.get('id'): (item.get('sourceRef'), item.get('targetRef'))
            for item in root.findall('.//bpmn:sequenceFlow', BPMN)
            if item.get('id')
        }
        connectors.update({
            item.get('id'): (item.get('sourceRef'), item.get('targetRef'))
            for item in root.findall('.//bpmn:association', BPMN)
            if item.get('id')
        })
        for edge in root.findall('.//bpmndi:BPMNEdge', BPMN):
            edge_id = edge.get('bpmnElement')
            seen_label = False
            for child in list(edge):
                child_name = local(child.tag)
                if child_name == 'BPMNLabel':
                    seen_label = True
                if child_name == 'waypoint' and seen_label:
                    ensure(False,
                           f'{path.name} edge {edge_id} has BPMNLabel before di:waypoint; Camunda engine rejects it')
            if edge_id not in connectors:
                continue
            source_id, target_id = connectors[edge_id]
            if source_id not in bounds_by_element or target_id not in bounds_by_element:
                continue
            waypoints = [
                (float(item.get('x', '0')), float(item.get('y', '0')))
                for item in edge.findall(f'{{{DI_URI}}}waypoint')
            ]
            ensure(len(waypoints) >= 2, f'{path.name} edge {edge_id} has less than two waypoints')
            ensure(waypoints[0] != waypoints[-1],
                   f'{path.name} edge {edge_id} has identical start/end waypoints')
            source_distance = distance_to_bounds(waypoints[0], bounds_by_element[source_id])
            target_distance = distance_to_bounds(waypoints[-1], bounds_by_element[target_id])
            ensure(source_distance <= 8 and target_distance <= 8,
                   f'{path.name} edge {edge_id} does not touch source/target shapes')

        sequence_flows = root.findall('.//bpmn:sequenceFlow', BPMN)
        incoming = {
            item.get('targetRef') for item in sequence_flows
            if item.get('targetRef')
        }
        outgoing = {
            item.get('sourceRef') for item in sequence_flows
            if item.get('sourceRef')
        }
        graph = defaultdict(list)
        for sequence_flow in sequence_flows:
            source_id = sequence_flow.get('sourceRef')
            target_id = sequence_flow.get('targetRef')
            if source_id and target_id:
                graph[source_id].append(target_id)

        for call_activity in root.findall('.//bpmn:callActivity', BPMN):
            call_id = call_activity.get('id')
            called_element = call_activity.get('calledElement')
            ensure(called_element,
                   f'{path.name} call activity {call_id} has no calledElement')
            ensure(called_element in all_process_ids,
                   f'{path.name} call activity {call_id} references missing process {called_element}')
            ensure(call_activity.get(f'{{{CAMUNDA_URI}}}calledElementBinding') == 'latest',
                   f'{path.name} call activity {call_id} must use calledElementBinding=latest')
            ensure(call_id in incoming and call_id in outgoing,
                   f'{path.name} call activity {call_id} must be connected by sequence flows')

        for business_rule_task in root.findall('.//bpmn:businessRuleTask', BPMN):
            task_id = business_rule_task.get('id')
            ensure(business_rule_task.get(f'{{{CAMUNDA_URI}}}decisionRef'),
                   f'{path.name} business rule task {task_id} has no Camunda decisionRef')
            ensure(business_rule_task.get(f'{{{CAMUNDA_URI}}}decisionRefBinding') == 'latest',
                   f'{path.name} business rule task {task_id} must use decisionRefBinding=latest')

        for expandable_tag in ['transaction', 'subProcess']:
            for expandable in root.findall(f'.//bpmn:{expandable_tag}', BPMN):
                expandable_id = expandable.get('id')
                shape = next(
                    (item for item in root.findall('.//bpmndi:BPMNShape', BPMN)
                     if item.get('bpmnElement') == expandable_id),
                    None
                )
                ensure(shape is not None and shape.get('isExpanded') == 'true',
                       f'{path.name} {expandable_tag} {expandable_id} must be drawn expanded in BPMN DI')

        for item in root.iter():
            tag = local(item.tag)
            node_id = item.get('id')
            if tag not in FLOW_NODE_TAGS or not node_id:
                continue
            is_compensation_handler = item.get('isForCompensation') == 'true'
            if tag not in {'startEvent', 'boundaryEvent'} and not is_compensation_handler:
                ensure(node_id in incoming, f'{path.name} flow node {node_id} has no incoming sequence flow')
            if tag not in {'endEvent', 'boundaryEvent'} and not is_compensation_handler:
                ensure(node_id in outgoing, f'{path.name} flow node {node_id} has no outgoing sequence flow')

        scopes = defaultdict(set)
        starts_by_scope = defaultdict(set)
        for item in root.iter():
            tag = local(item.tag)
            node_id = item.get('id')
            if tag not in FLOW_NODE_TAGS or not node_id or item.get('isForCompensation') == 'true':
                continue
            scope = process_scope(item, parents)
            if scope is None:
                continue
            scopes[scope].add(node_id)
            if tag in {'startEvent', 'boundaryEvent'}:
                starts_by_scope[scope].add(node_id)

        for scope, node_ids in scopes.items():
            start_ids = starts_by_scope[scope]
            if not start_ids:
                continue
            seen = set(start_ids)
            queue = deque(start_ids)
            while queue:
                node_id = queue.popleft()
                for next_id in graph[node_id]:
                    if next_id in node_ids and next_id not in seen:
                        seen.add(next_id)
                        queue.append(next_id)
            unreachable = sorted(node_ids - seen)
            ensure(not unreachable, f'{path.name} scope {scope} has unreachable flow nodes: {unreachable}')

    print('Camunda BPMN visual model contract checks passed')
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except AssertionError as exc:
        print(exc)
        sys.exit(1)
