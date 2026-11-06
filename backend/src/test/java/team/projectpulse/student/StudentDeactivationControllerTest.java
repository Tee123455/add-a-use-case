package team.projectpulse.student;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import team.projectpulse.section.Section;
import team.projectpulse.student.converter.StudentToStudentDtoConverter;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StudentDeactivationControllerTest {
    // Oracle: UC-STU-deactivate-student main step 6 / extension 6a; API contract T1/T4.
    @ParameterizedTest
    @CsvSource({"true,Student deactivated successfully", "false,Student is already deactivated"})
    void returnsSpecifiedOutcomeAndInactiveStudent(boolean changed, String message) throws Exception {
        StudentService service = mock(StudentService.class);
        Student student = new Student("john", "John", "Smith", "john@abc.edu", "secret", false, "student");
        student.setId(4);
        student.setSection(new Section());
        when(service.deactivateStudent(4)).thenReturn(new StudentService.DeactivationResult(student, changed));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StudentController(service, null,
                new StudentToStudentDtoConverter(), null))
                .addPlaceholderValue("api.endpoint.base-url", "/api/v1").build();
        mvc.perform(put("/api/v1/students/4/deactivate"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.flag").value(true))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.data.id").value(4)).andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }
}
