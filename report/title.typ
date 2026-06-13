#let title_page(
  lab_no: 4,
  subject: "бизнес-логика программных систем",
  variant: "101",
  student: "Григорий Садовой\nБайрамгулов Мунир",
  teacher: "Кривоносов Егор Дмитриевич",
  logo: "logo.png",
) = [
  #set page(
    paper: "a4",
    margin: (top: 20mm, bottom: 20mm, left: 20mm, right: 20mm),
  )

  #align(center)[
    #image(logo, width: 58%)

    #v(16pt)
    Университет ИТМО\
    Мегафакультет компьютерных технологий и управления\
    Факультет программной инженерии и компьютерной техники

    #v(56pt)
    #text(size: 18pt, weight: "bold")[Отчет по лабораторной работе №#lab_no]

    #v(8pt)
    #text(size: 14pt)[по дисциплине "#subject"]

    #v(16pt)
    #text(size: 13pt)[Вариант: #variant]

    #v(80pt)
  ]

  #align(right)[
    Выполнили:\
    #student

    #v(16pt)
    Преподаватель:\
    #teacher
  ]

  #v(1fr)

  #align(center)[
    Санкт-Петербург, 2026
  ]

  #pagebreak()
]
