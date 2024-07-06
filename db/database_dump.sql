--
-- PostgreSQL database dump
--

-- Dumped from database version 16.3
-- Dumped by pg_dump version 16.3

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: student_schedule; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.student_schedule (
    student_id integer NOT NULL,
    day_of_week character varying(10) NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone,
    zone integer NOT NULL
);


ALTER TABLE public.student_schedule OWNER TO postgres;

--
-- Name: students; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.students (
    student_id integer NOT NULL,
    student_name character varying(100) NOT NULL,
    faculty character varying(100) NOT NULL,
    hours_studied_today interval DEFAULT '00:00:00'::interval,
    hours_studied_semester interval DEFAULT '00:00:00'::interval,
    last_update_date date
);


ALTER TABLE public.students OWNER TO postgres;

--
-- Name: students_student_id_seq; Type: SEQUENCE; Schema: public; Owner: postgres
--

CREATE SEQUENCE public.students_student_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


ALTER SEQUENCE public.students_student_id_seq OWNER TO postgres;

--
-- Name: students_student_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: postgres
--

ALTER SEQUENCE public.students_student_id_seq OWNED BY public.students.student_id;


--
-- Name: zone_hours; Type: TABLE; Schema: public; Owner: postgres
--

CREATE TABLE public.zone_hours (
    student_id integer NOT NULL,
    zone integer NOT NULL,
    hours_studied_semester interval
);


ALTER TABLE public.zone_hours OWNER TO postgres;

--
-- Name: students student_id; Type: DEFAULT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.students ALTER COLUMN student_id SET DEFAULT nextval('public.students_student_id_seq'::regclass);


--
-- Data for Name: student_schedule; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.student_schedule (student_id, day_of_week, start_time, end_time, zone) FROM stdin;
0	Monday	14:00:00	16:00:00	1
0	Monday	18:00:00	20:00:00	0
0	Tuesday	10:00:00	12:00:00	1
0	Friday	10:00:00	12:00:00	0
0	Friday	14:00:00	16:00:00	1
0	Friday	18:00:00	20:00:00	0
0	Wednesday	16:00:00	18:00:00	0
0	Thursday	16:00:00	18:00:00	1
0	Monday	08:00:00	10:00:00	0
0	Wednesday	08:00:00	10:00:00	1
\.


--
-- Data for Name: students; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.students (student_id, student_name, faculty, hours_studied_today, hours_studied_semester, last_update_date) FROM stdin;
2	Paulet Tudor-Stefan	Facultatea de Informatica	00:00:00	00:00:00	\N
4	Ceausu Darius-Matei	Facultatea de Biologie	00:00:00	00:00:00	\N
1	Adochiei Tudor	FEAA	00:00:00	00:00:00	\N
0	Teodorescu Calin-Ioan	Facultatea de Informatica	01:00:00	06:15:00	2024-06-07
\.


--
-- Data for Name: zone_hours; Type: TABLE DATA; Schema: public; Owner: postgres
--

COPY public.zone_hours (student_id, zone, hours_studied_semester) FROM stdin;
1	0	00:00:00
1	1	00:00:00
2	0	00:00:00
2	1	00:00:00
3	0	00:00:00
3	1	00:00:00
0	0	120:43:00
0	1	101:00:00
\.


--
-- Name: students_student_id_seq; Type: SEQUENCE SET; Schema: public; Owner: postgres
--

SELECT pg_catalog.setval('public.students_student_id_seq', 1, true);


--
-- Name: student_schedule student_schedule_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.student_schedule
    ADD CONSTRAINT student_schedule_pkey PRIMARY KEY (student_id, day_of_week, start_time, zone);


--
-- Name: students students_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.students
    ADD CONSTRAINT students_pkey PRIMARY KEY (student_id);


--
-- Name: zone_hours zone_hours_pkey; Type: CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.zone_hours
    ADD CONSTRAINT zone_hours_pkey PRIMARY KEY (student_id, zone);


--
-- Name: student_schedule student_schedule_student_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: postgres
--

ALTER TABLE ONLY public.student_schedule
    ADD CONSTRAINT student_schedule_student_id_fkey FOREIGN KEY (student_id) REFERENCES public.students(student_id);


--
-- PostgreSQL database dump complete
--

