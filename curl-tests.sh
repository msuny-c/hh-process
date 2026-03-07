

BASE_URL="http://localhost:8080/api/v1"

echo "1. Create vacancy"
curl -s -X POST "$BASE_URL/vacancies" \
  -H "Content-Type: application/json" \
  -d '{
    "title":"Java Developer",
    "description":"Разработка backend на Spring Boot"
  }'
echo -e "\n"

echo "2. Create candidate"
curl -s -X POST "$BASE_URL/candidates" \
  -H "Content-Type: application/json" \
  -d '{
    "fullName":"Иван Петров",
    "email":"ivan@example.com",
    "phone":"+79990000000",
    "resumeText":"Java, Spring, PostgreSQL"
  }'
echo -e "\n"

echo "3. Create application"
curl -s -X POST "$BASE_URL/applications" \
  -H "Content-Type: application/json" \
  -d '{
    "vacancyId":1,
    "candidateId":1,
    "coverLetter":"Хочу работать у вас"
  }'
echo -e "\n"

echo "4. Validate application"
curl -s -X POST "$BASE_URL/applications/1/validate"
echo -e "\n"

echo "5. Invite candidate"
curl -s -X POST "$BASE_URL/applications/1/invite" \
  -H "Content-Type: application/json" \
  -d '{
    "comment":"Приглашаем на техническое интервью"
  }'
echo -e "\n"

echo "6. Candidate accepts invitation"
curl -s -X POST "$BASE_URL/applications/1/accept" \
  -H "Content-Type: application/json" \
  -d '{
    "comment":"Подтверждаю участие"
  }'
echo -e "\n"

echo "7. Application history"
curl -s "$BASE_URL/applications/1/history"
echo -e "\n"
