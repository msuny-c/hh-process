ALTER TABLE vacancies
    ADD COLUMN camunda_process_instance_id VARCHAR(64);

ALTER TABLE applications
    ADD COLUMN camunda_process_instance_id VARCHAR(64);

CREATE INDEX idx_vacancies_camunda_process ON vacancies(camunda_process_instance_id);
CREATE INDEX idx_applications_camunda_process ON applications(camunda_process_instance_id);
