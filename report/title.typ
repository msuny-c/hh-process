#let titlepage(
  lab_no: 1,
  subject: "",
  variant: "",
  student: "",
  teacher: "",
  logo: "logo.png",
) = [
  #set text(region: "RU", lang: "RU")

  #set page(
    paper: "a4",
    margin: (top: 20mm, bottom: 20mm, left: 20mm, right: 20mm),
  )

  #set par(first-line-indent: 0pt, leading: 1.25em)

  #place(top + left, dx: 0mm, dy: 0mm)[
    #box(width: 85mm)[
      #align(center)[
        #text(weight: "bold")[
          Университет ИТМО\
          Мегафакультет компьютерных технологий\
          и управления\
          Факультет программной инженерии и\
          компьютерной техники
        ]
      ]
    ]
  ]

  #place(top + right, dx: 0mm, dy: 3mm)[
    #image(logo, width: 60mm)
  ]

  #place(top, dy: 105mm)[
    #box(width: 100%)[
      #align(center)[
        #stack(spacing: 0pt)[
          #text(size: 18pt, weight: "bold")[Отчет]\
          #text(size: 18pt, weight: "bold")[по лабораторной работе №#lab_no]\
          #text(size: 18pt, weight: "bold")[«#subject»]
          #v(4pt)
          #text(size: 18pt)[Вариант #variant]
        ]
      ]
    ]
  ]

  #place(top + right, dx: 40mm, dy: 175mm)[
    #box(width: 80mm)[
      #align(left)[
        #stack(spacing: 12pt)[
          #stack(spacing: 2pt)[
            #text(weight: "bold")[Выполнили:]\
            #student
          ]
          #stack(spacing: 2pt)[
            #text(weight: "bold")[Преподаватель]\
            #teacher
          ]
        ]
      ]
    ]
  ]

  #place(bottom, dy: -5mm)[
    #box(width: 100%)[
      #align(center)[
        #text(size: 13pt)[
          Санкт-Петербург, #datetime.today().year() г.]
      ]
    ]
  ]

  #pagebreak()
]